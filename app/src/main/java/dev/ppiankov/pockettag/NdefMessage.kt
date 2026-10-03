package dev.ppiankov.pockettag

// Pure encoding and APDU logic for an NFC Forum Type 4 Tag (mapping version 2.0).
// No Android imports, so all of it is covered by JVM unit tests.

// WO-3: the size error applies to the complete NDEF file for any content type.
class NdefTooLargeException(message: String) : IllegalArgumentException(message)

object NdefMessage {
    // NDEF record header: MB | ME | SR, TNF = 0x01 (NFC Forum well-known type).
    const val HEADER_SHORT_WELL_KNOWN: Byte = 0xD1.toByte()
    const val TYPE_URI: Byte = 0x55 // 'U'

    // Short records encode the payload length in one byte.
    const val MAX_SHORT_PAYLOAD = 255

    // NFC Forum URI RTD abbreviation table (subset). Longest prefixes first so
    // "https://www." wins over "https://".
    private val URI_PREFIXES: List<Pair<String, Byte>> = listOf(
        "https://www." to 0x02,
        "http://www." to 0x01,
        "https://" to 0x04,
        "http://" to 0x03,
        "mailto:" to 0x06,
        "tel:" to 0x05,
    )
    const val PREFIX_NONE: Byte = 0x00

    /** Splits [url] into its URI identifier code and the remaining bytes. */
    fun uriPayload(url: String): ByteArray {
        val match = URI_PREFIXES.firstOrNull { (prefix, _) -> url.startsWith(prefix) }
        val code = match?.second ?: PREFIX_NONE
        val rest = if (match != null) url.substring(match.first.length) else url
        return byteArrayOf(code) + rest.toByteArray(Charsets.UTF_8)
    }

    // WO-3: retain short URI bytes while allowing payloads beyond one-byte lengths.
    fun uriRecord(url: String): ByteArray =
        record(HEADER_SHORT_WELL_KNOWN, byteArrayOf(TYPE_URI), uriPayload(url))

    // WO-3: contact cards use a single media-type record with the same length rules.
    fun mimeRecord(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.isNotEmpty() && typeBytes.size <= MAX_SHORT_PAYLOAD) {
            "MIME type must contain between 1 and 255 ASCII bytes."
        }
        require(type.all { it.code in 0x21..0x7E }) { "MIME type must be ASCII." }
        return record(HEADER_SHORT_MIME, typeBytes, payload)
    }

    // WO-3: clearing SR changes the length field to four big-endian bytes.
    private fun record(shortHeader: Byte, type: ByteArray, payload: ByteArray): ByteArray {
        val short = payload.size <= MAX_SHORT_PAYLOAD
        val header = if (short) shortHeader else (shortHeader.toInt() and SR_MASK.inv()).toByte()
        val length = if (short) byteArrayOf(payload.size.toByte()) else byteArrayOf(
            (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte(),
        )
        return byteArrayOf(header, type.size.toByte()) + length + type + payload
    }

    fun ndefFile(url: String): ByteArray = ndefFile(uriRecord(url))

    // WO-3: the advertised file limit includes NLEN, regardless of record type.
    fun ndefFile(message: ByteArray): ByteArray {
        if (message.size > Type4Constants.MAX_NDEF_FILE_SIZE - NLEN_SIZE) {
            throw NdefTooLargeException("Item exceeds the 1024-byte tag file limit.")
        }
        return byteArrayOf(
            (message.size shr 8 and 0xFF).toByte(),
            (message.size and 0xFF).toByte(),
        ) + message
    }

    private const val HEADER_SHORT_MIME: Byte = 0xD2.toByte() // WO-3: MB, ME, SR, media TNF.
    private const val SR_MASK = 0x10 // WO-3: short-record flag in the record header.
    private const val NLEN_SIZE = 2 // WO-3: Type 4 file length prefix in bytes.
}

object Type4Constants {
    const val NDEF_AID_HEX = "D2760000850101"
    val NDEF_AID: ByteArray = hex(NDEF_AID_HEX)
    val CC_FILE_ID: ByteArray = hex("E103")
    val NDEF_FILE_ID: ByteArray = hex("E104")

    val SW_OK: ByteArray = hex("9000")
    val SW_FILE_NOT_FOUND: ByteArray = hex("6A82")
    val SW_WRONG_P1P2: ByteArray = hex("6B00")
    val SW_INS_NOT_SUPPORTED: ByteArray = hex("6D00")
    val SW_CLA_NOT_SUPPORTED: ByteArray = hex("6E00")

    const val CC_LEN = 0x000F
    const val MAPPING_VERSION_2_0 = 0x20

    // Max R-APDU / C-APDU data sizes advertised to the reader; conservative values that
    // every reader seen in practice accepts.
    const val MLE = 0x003B
    const val MLC = 0x0034

    // WO-3: whole tag file, including the two-byte NLEN (1022 message bytes remain).
    const val MAX_NDEF_FILE_SIZE = 1024

    const val READ_ACCESS_GRANTED = 0x00
    const val WRITE_ACCESS_DENIED = 0xFF

    /** Capability Container file advertising one read-only NDEF file. */
    val CC_FILE: ByteArray = byteArrayOf(
        (CC_LEN shr 8).toByte(), CC_LEN.toByte(),
        MAPPING_VERSION_2_0.toByte(),
        (MLE shr 8).toByte(), MLE.toByte(),
        (MLC shr 8).toByte(), MLC.toByte(),
        0x04, // NDEF File Control TLV: T
        0x06, // L
        NDEF_FILE_ID[0], NDEF_FILE_ID[1],
        (MAX_NDEF_FILE_SIZE shr 8).toByte(), MAX_NDEF_FILE_SIZE.toByte(),
        READ_ACCESS_GRANTED.toByte(),
        WRITE_ACCESS_DENIED.toByte(),
    )

    fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * Type 4 Tag APDU state machine: SELECT AID → SELECT CC → READ CC → SELECT NDEF → READ NDEF.
 * [ndefFileProvider] is called on each application SELECT, so a URL change takes effect on
 * the next tap without restarting anything.
 */
class Type4Tag(private val ndefFileProvider: () -> ByteArray?) {
    private enum class Selected { NONE, APP, CC, NDEF }

    private var selected = Selected.NONE
    private var ndefFile: ByteArray = ByteArray(0)

    fun reset() {
        selected = Selected.NONE
    }

    fun process(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return Type4Constants.SW_WRONG_P1P2
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        if (cla != 0x00) return Type4Constants.SW_CLA_NOT_SUPPORTED
        return when (ins) {
            INS_SELECT -> select(apdu)
            INS_READ_BINARY -> readBinary(apdu)
            else -> Type4Constants.SW_INS_NOT_SUPPORTED
        }
    }

    private fun select(apdu: ByteArray): ByteArray {
        val p1 = apdu[2].toInt() and 0xFF
        val data = commandData(apdu) ?: return Type4Constants.SW_WRONG_P1P2
        return when {
            p1 == P1_SELECT_BY_NAME && data.contentEquals(Type4Constants.NDEF_AID) -> {
                val file = ndefFileProvider()
                if (file == null) {
                    selected = Selected.NONE
                    Type4Constants.SW_FILE_NOT_FOUND
                } else {
                    ndefFile = file
                    selected = Selected.APP
                    Type4Constants.SW_OK
                }
            }
            p1 == P1_SELECT_BY_FILE_ID && selected != Selected.NONE &&
                data.contentEquals(Type4Constants.CC_FILE_ID) -> {
                selected = Selected.CC
                Type4Constants.SW_OK
            }
            p1 == P1_SELECT_BY_FILE_ID && selected != Selected.NONE &&
                data.contentEquals(Type4Constants.NDEF_FILE_ID) -> {
                selected = Selected.NDEF
                Type4Constants.SW_OK
            }
            else -> Type4Constants.SW_FILE_NOT_FOUND
        }
    }

    private fun readBinary(apdu: ByteArray): ByteArray {
        val file = when (selected) {
            Selected.CC -> Type4Constants.CC_FILE
            Selected.NDEF -> ndefFile
            else -> return Type4Constants.SW_FILE_NOT_FOUND
        }
        val offset = ((apdu[2].toInt() and 0x7F) shl 8) or (apdu[3].toInt() and 0xFF)
        if (offset > file.size) return Type4Constants.SW_WRONG_P1P2
        // Le of 0x00 (or absent) means "up to 256 bytes" in short APDUs.
        val le = if (apdu.size >= 5) apdu[4].toInt() and 0xFF else 0
        val requested = if (le == 0) MAX_SHORT_LE else le
        val end = minOf(offset + requested, file.size)
        return file.copyOfRange(offset, end) + Type4Constants.SW_OK
    }

    private fun commandData(apdu: ByteArray): ByteArray? {
        if (apdu.size < 5) return null
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return null
        return apdu.copyOfRange(5, 5 + lc)
    }

    private companion object {
        const val INS_SELECT = 0xA4
        const val INS_READ_BINARY = 0xB0
        const val P1_SELECT_BY_FILE_ID = 0x00
        const val P1_SELECT_BY_NAME = 0x04
        const val MAX_SHORT_LE = 256
    }
}
