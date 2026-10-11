package tech.hegnes.thermostat.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import java.io.IOException

class TagException(message: String) : Exception(message)

/**
 * Reads and writes the single NDEF Text record that a Heatit NFC thermostat exposes.
 * All calls block and must run off the main thread (the reader-mode callback thread is fine).
 */
object ThermostatTag {

    fun read(tag: Tag): String = withNdef(tag) { ndef -> readText(ndef) }

    /** Writes [text] and reads it back on the same connection. Returns the text read back. */
    fun write(tag: Tag, text: String): String = withNdef(tag) { ndef ->
        if (!ndef.isWritable) throw TagException("The thermostat's tag is read-only")
        val message = NdefMessage(NdefRecord.createTextRecord("en", text))
        if (message.byteArrayLength > ndef.maxSize) {
            throw TagException("Message is ${message.byteArrayLength} bytes, tag holds ${ndef.maxSize}")
        }
        ndef.writeNdefMessage(message)
        val readBack = readText(ndef)
        if (readBack != text) throw TagException("Verification failed: the tag holds different data after writing")
        readBack
    }

    private fun readText(ndef: Ndef): String {
        val message = ndef.ndefMessage ?: throw TagException("The tag is empty")
        val record = message.records.firstOrNull {
            it.tnf == NdefRecord.TNF_WELL_KNOWN && it.type.contentEquals(NdefRecord.RTD_TEXT)
        } ?: throw TagException("No NDEF Text record on the tag")
        return decodeTextPayload(record.payload)
    }

    /** NDEF Text payload: status byte (bit 7 = UTF-16, bits 0-5 = language length), language, text. */
    fun decodeTextPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt()
        val langLength = status and 0x3F
        val charset = if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
        return String(payload, 1 + langLength, payload.size - 1 - langLength, charset)
    }

    private inline fun <T> withNdef(tag: Tag, block: (Ndef) -> T): T {
        val ndef = Ndef.get(tag) ?: throw TagException("This tag does not support NDEF – is it a Heatit thermostat?")
        try {
            ndef.connect()
            return block(ndef)
        } catch (e: IOException) {
            throw TagException("Connection lost – hold the phone still against the thermostat and try again")
        } catch (e: android.nfc.FormatException) {
            throw TagException("The tag contains malformed NDEF data")
        } finally {
            try {
                ndef.close()
            } catch (_: IOException) {
            }
        }
    }
}
