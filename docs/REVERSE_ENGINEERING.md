# How the protocol was reverse-engineered

This guide shows how [PROTOCOL.md](PROTOCOL.md) was worked out, so you can check it, extend it, or do the same for other NFC-configured devices. Everything here works on Windows, macOS or Linux.

> This repository does **not** include the official APK or any decompiled code from it. Download the app yourself from a source you trust, and use what you learn for interoperability with hardware you own.

## Tools

| Tool | Used for |
| --- | --- |
| [jadx](https://github.com/skylot/jadx) 1.5.x | Decompiling the APK to Java and decoding its resources |
| Any unzip tool | Opening `.xapk` / `.apks` bundles |
| Python 3.10+ | Checking the CRC and encoding ideas against the decompiled code |
| An NFC phone | Reading the real thermostat (this repo's app, or NXP TagInfo / NFC Tools) |

## 1. Get the APK

The official app is **Heatit NFC Thermostat** (package `com.protoskoop.android.thermofloorthermostat`). Mirror sites often serve it as an **XAPK**, which is just a zip:

```
app.xapk
├── com.protoskoop.android.thermofloorthermostat.apk   ← base APK, all the code
├── config.xxxhdpi.apk                                 ← density split, icons only
├── icon.png
└── manifest.json
```

```bash
mkdir xapk && cd xapk
unzip ../app.xapk
```

## 2. Decompile

```bash
jadx -d out --show-bad-code com.protoskoop.android.thermofloorthermostat.apk
```

On Windows with jadx installed from winget, you may only have the GUI jar. The CLI class is in the same jar:

```powershell
java -cp jadx-gui-1.5.1-all.jar jadx.cli.JadxCLI -d out --show-bad-code base.apk
```

The app isn't obfuscated, so the result is clean. The interesting part is small:

```
out/sources/com/protoskoop/android/thermofloorthermostat/
├── NfcData.java        ← the whole protocol: parse, build, NDEF read/write
├── Crc.java            ← CRC-16/CCITT-FALSE
├── TempTime.java       ← one schedule point (minutes, half-degrees)
├── MainActivity.java   ← UI ↔ protocol mapping, NFC foreground dispatch
└── …                   ← splash, welcome screen, custom views
out/resources/
├── AndroidManifest.xml
└── res/values/arrays.xml  ← labels for every enum (modes, sensor types, …)
```

## 3. Find the transport

Search for the NFC API classes:

```bash
grep -rn "android.nfc" out/sources/com/protoskoop
```

The only technology used is `android.nfc.tech.Ndef`, with `getNdefMessage()` and `writeNdefMessage()`, and the record is created with `NdefRecord.createTextRecord("en", …)`. There are no `transceive` calls. That means the thermostat is a plain NDEF tag and any NFC tool can talk to it. See [PROTOCOL.md §2](PROTOCOL.md#2-nfc-transport).

## 4. Read the protocol from `NfcData`

`NfcData.updateNfcDataStr()` builds the outgoing text one `StringBuilder.append` at a time, which gives you the key order and the `String.format` pattern for each field (`%02x`, `%01x`, …). `NfcData.parseNfcData()` is the reverse, and shows which fields are hex (`Integer.valueOf(x, 16)`) and which are decimal (`Integer.parseInt`).

From there, every scale and offset comes from following the values back into `MainActivity`:

| What to look for | Where | What it tells you |
| --- | --- | --- |
| `(spCo * 10) / 5` | `updateNfcDataStr` | Setpoints are in 0.5 °C units |
| `calibValue + SOURCE_ANY` (= −256) | `parseNfcData` | Calibration is a signed byte |
| `seekBar.getProgress() + 14 / + 10 / − 40` | `getNfcValuesFromUI` | Slider offsets for limits and calibration |
| `getExternalSensorType()` switch | `MainActivity` | The sensor code remap (menu index ≠ wire code) |
| `arrays.xml` | resources | Human-readable names for every enum |
| `constructTempTimePairs` / `parseDayValues` | `NfcData` | The 7-field day format and its clean-up rules |
| `(DAY_OF_WEEK + 5) % 7` | `updateNfcDataStr` | `tm:` weekday, 0 = Monday |

## 5. Pin down the checksum

`Crc.getCCITT` uses polynomial `4129` (`0x1021`) and preset `65535` (`0xFFFF`), MSB-first with no final XOR. That is **CRC-16/CCITT-FALSE**. Two details that are easy to get wrong:

1. The CRC is computed **after** `"rc:"` has been appended, so those three characters are covered.
2. The result is formatted with `%04x` (lowercase, zero-padded).

Confirm with the standard check value:

```bash
python tools/heatit_nfc.py selftest
```

## 6. Check against the real device

Static analysis only shows what the app sends. Reading the real thermostat then showed:

- the firmware writes `i2c` on the first line (the app writes `nfc`)
- two extra lines the app ignores: `vn:` (firmware version) and `sn:` (serial number)
- no `tm:` line
- an `rc:` value that matches the same CRC

You can capture your own data with the app in this repo (**Settings → Raw NFC data**), NXP TagInfo, or NFC Tools, and decode it:

```bash
python tools/heatit_nfc.py decode capture.txt
```

**Please remove `sn:` before sharing captures.**

## 7. Make your own client

A minimal client does four things:

1. Read the NDEF Text record.
2. Parse the `key:value` lines, ignoring the author line and unknown keys.
3. Change the fields you want, keeping everything else as read.
4. Rebuild the full message, add `tm:` and `rc:`, and write it as one Text record (`en`, UTF-8).

Reference implementations in this repository:

| Language | File |
| --- | --- |
| Kotlin (Android) | [`ThermostatProtocol.kt`](../android/app/src/main/java/tech/hegnes/thermostat/protocol/ThermostatProtocol.kt), [`ThermostatTag.kt`](../android/app/src/main/java/tech/hegnes/thermostat/nfc/ThermostatTag.kt) |
| Python | [`tools/heatit_nfc.py`](../tools/heatit_nfc.py) |

To write from a PC, use [nfcpy](https://nfcpy.readthedocs.io/) with a USB reader such as an ACR122U (Type 2/3/4 tags):

```python
import nfc, ndef
from heatit_nfc import decode, encode

def on_connect(tag):
    current = decode(tag.ndef.records[0].text)
    current["mode"] = 3                     # Schedule
    tag.ndef.records = [ndef.TextRecord(encode(current), "en")]
    return True

with nfc.ContactlessFrontend("usb") as clf:
    clf.connect(rdwr={"on-connect": on_connect})
```

This snippet hasn't been tested against a thermostat. If the tag turns out to be ISO 15693 (Type 5), use a phone or a reader SDK with NFC-V support.
