#!/usr/bin/env python3
"""Encode and decode Heatit NFC thermostat (product code ou047) payloads.

See docs/PROTOCOL.md for the format. No dependencies beyond the standard library.

Usage:
    python heatit_nfc.py decode capture.txt     # pretty-print a captured payload ("-" = stdin)
    python heatit_nfc.py example                # print an example write payload
    python heatit_nfc.py selftest               # check the codec against known payloads
"""
from __future__ import annotations

import json
import sys
from datetime import datetime

PRODUCT = "ou047"
MODES = {0: "Off", 1: "Comfort", 2: "Eco", 3: "Schedule"}
REGULATION = {0: "A (room sensor)", 1: "AF (room + floor limits)", 2: "F (floor sensor)", 3: "A2 (external sensor)"}
SENSORS = {0: "10k", 1: "12k", 2: "15k", 3: "22k", 4: "33k", 5: "47k", 6: "6.8k"}


def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else crc << 1
            crc &= 0xFFFF
    return crc


def _day(base_c: float, points: list[tuple[int, float]]) -> str:
    """base_c: temperature from 00:00. points: up to 6 (minute, temp_c) switch points."""
    if len(points) > 6:
        raise ValueError("a day holds at most 6 switch points")
    fields = [f"{round(base_c * 2):02x}"]
    fields += [f"{m:03x}{round(t * 2):02x}" for m, t in points]
    fields += ["fffff"] * (7 - len(fields))
    return ",".join(fields)


def encode(s: dict, now: datetime | None = None) -> str:
    """Build a full write payload (as the official app does), including tm: and rc:."""
    now = now or datetime.now()
    hl = lambda c: f"{round(c * 2):02x}"  # noqa: E731
    lines = [
        "nfc",
        f"pr:{PRODUCT}",
        f"md:{s['mode']}",
        f"sp:{hl(s['comfort'])},{hl(s['eco'])}",
        f"op:{s['reg_mode']}",
        f"st:{s['floor_sensor']},{s['ext_sensor']}",
        f"ca:{round(s['calibration'] * 10) & 0xFF:02x}",
        f"br:{s['brightness']:01x}",
        "lm:" + ",".join(hl(s[k]) for k in ("a_hi", "a_lo", "f_hi", "f_lo")),
        f"cl:{s['child_lock']}",
        f"ds:{s['display']}",
        f"as:{s['adaptive_start']}",
    ]
    for i, (base, pts) in enumerate(s["schedule"]):
        lines.append(f"d{i}:{_day(base, pts)}")
    lines.append(f"tm:{now.hour:02x},{now.minute:02x},{now.second:02x},{now.weekday()}")
    body = "\n".join(lines) + "\nrc:"
    return body + f"{crc16_ccitt_false(body.encode()):04x}"


def decode(text: str) -> dict:
    """Parse a payload written by the app or by the thermostat."""
    text = text.replace("\r\n", "\n").strip("\n")
    lines = text.split("\n")
    kv: dict[str, str] = {}
    for line in lines[1:]:
        if len(line) > 3 and line[2] == ":":
            kv.setdefault(line[:2], line[3:].strip())
    if kv.get("pr") != PRODUCT:
        raise ValueError(f"not an {PRODUCT} thermostat (pr:{kv.get('pr')})")
    out: dict = {"author": lines[0], "mode": int(kv["md"])}
    co, eco = kv["sp"].split(",")
    out["comfort"], out["eco"] = int(co, 16) / 2, int(eco, 16) / 2
    if "vn" in kv:
        out["firmware"] = ".".join(str(int(x, 16)) for x in kv["vn"].split(","))
    if "sn" in kv:
        out["serial"] = kv["sn"]
    if "op" in kv:
        out["reg_mode"] = int(kv["op"])
        out["floor_sensor"], out["ext_sensor"] = map(int, kv["st"].split(","))
        ca = int(kv["ca"], 16)
        out["calibration"] = (ca - 256 if ca > 127 else ca) / 10
        out["brightness"] = int(kv["br"], 16)
        ahi, alo, fhi, flo = (int(x, 16) / 2 for x in kv["lm"].split(","))
        out.update(a_hi=ahi, a_lo=alo, f_hi=fhi, f_lo=flo)
        out.update(child_lock=int(kv["cl"]), display=int(kv["ds"]), adaptive_start=int(kv["as"]))
    out["schedule"] = []
    for d in range(7):
        f = kv.get(f"d{d}", "2d").split(",")
        pts = [(int(x[:3], 16), int(x[3:], 16) / 2) for x in f[1:] if x != "fffff" and len(x) == 5]
        out["schedule"].append((int(f[0], 16) / 2, pts))
    if "tm" in kv:
        out["clock"] = kv["tm"]
    if "rc" in kv:
        body = text[: text.index("rc:") + 3]
        out["crc_ok"] = crc16_ccitt_false(body.encode()) == int(kv["rc"][:4], 16)
    return out


def describe(d: dict) -> str:
    days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    rows = [
        f"author        {d['author']}",
        f"mode          {MODES.get(d['mode'], d['mode'])}",
        f"comfort/eco   {d['comfort']:.1f} / {d['eco']:.1f} °C",
    ]
    if "firmware" in d:
        rows.append(f"firmware      {d['firmware']}")
    if "reg_mode" in d:
        rows += [
            f"regulation    {REGULATION.get(d['reg_mode'], d['reg_mode'])}",
            f"sensors       floor {SENSORS.get(d['floor_sensor'])}, external {SENSORS.get(d['ext_sensor'])}",
            f"calibration   {d['calibration']:+.1f} °C",
            f"brightness    {'auto' if d['brightness'] >= 10 else d['brightness']}",
            f"room limits   {d['a_lo']:.1f} – {d['a_hi']:.1f} °C",
            f"floor limits  {d['f_lo']:.1f} – {d['f_hi']:.1f} °C",
            f"child lock    {'on' if d['child_lock'] else 'off'}",
            f"display       {'on' if d['display'] else 'off'}",
            f"adaptive      {'on' if d['adaptive_start'] else 'off'}",
        ]
    for name, (base, pts) in zip(days, d["schedule"]):
        changes = ", ".join(f"{m // 60:02d}:{m % 60:02d}→{t:.1f}" for m, t in pts)
        rows.append(f"{name}           {base:.1f} °C" + (f", {changes}" if changes else ""))
    if "crc_ok" in d:
        rows.append(f"checksum      {'OK' if d['crc_ok'] else 'MISMATCH'}")
    return "\n".join(rows)


EXAMPLE_SETTINGS = dict(
    mode=3, comfort=22.5, eco=18.0, reg_mode=0, floor_sensor=0, ext_sensor=0,
    calibration=0.0, brightness=5, a_hi=40.0, a_lo=5.0, f_hi=27.0, f_lo=5.0,
    child_lock=0, display=1, adaptive_start=0,
    schedule=[(18.0, [(420, 22.5), (480, 18.0), (960, 22.5), (1380, 18.0)])] * 5 + [(22.5, [])] * 2,
)

# Captured from a real thermostat; serial replaced and CRC recomputed.
DEVICE_CAPTURE = (
    "i2c\npr:ou047\nvn:01,01,00\nsn:1234567890\nmd:2\nsp:32,32\nop:2\nst:0,0\nca:00\nbr:3\n"
    "lm:50,0a,50,0a\ncl:0\nds:1\nas:0\n"
    + "".join(f"d{i}:2a,fffff,fffff,fffff,fffff,fffff,fffff\n" for i in range(7))
    + "rc:c657"
)


def selftest() -> None:
    assert crc16_ccitt_false(b"123456789") == 0x29B1, "CRC check value"
    msg = encode(EXAMPLE_SETTINGS, datetime(2026, 10, 10, 14, 30, 0))
    assert msg.endswith("tm:0e,1e,00,5\nrc:61c7"), msg
    back = decode(msg)
    assert back["crc_ok"] and back["schedule"] == EXAMPLE_SETTINGS["schedule"]
    dev = decode(DEVICE_CAPTURE)
    assert dev["crc_ok"] and dev["author"] == "i2c" and dev["firmware"] == "1.1.0"
    assert dev["mode"] == 2 and dev["reg_mode"] == 2 and dev["schedule"][0] == (21.0, [])
    print("selftest OK")


def main(argv: list[str]) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")  # the ° sign on Windows consoles
    if len(argv) < 2 or argv[1] not in {"decode", "example", "selftest"}:
        print(__doc__)
        return 2
    if argv[1] == "selftest":
        selftest()
    elif argv[1] == "example":
        print(encode(EXAMPLE_SETTINGS))
    else:
        src = argv[2] if len(argv) > 2 else "-"
        text = sys.stdin.read() if src == "-" else open(src, encoding="utf-8").read()
        d = decode(text)
        print(describe(d))
        if "--json" in argv:
            print(json.dumps(d, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
