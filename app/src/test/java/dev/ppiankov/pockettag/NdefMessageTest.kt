package dev.ppiankov.pockettag

import dev.ppiankov.pockettag.Type4Constants.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NdefMessageTest {

    @Test
    fun uriPrefixCodes() {
        assertEquals(0x04.toByte(), NdefMessage.uriPayload("https://a.dev")[0])
        assertEquals(0x02.toByte(), NdefMessage.uriPayload("https://www.a.dev")[0])
        assertEquals(0x03.toByte(), NdefMessage.uriPayload("http://a.dev")[0])
        assertEquals(0x01.toByte(), NdefMessage.uriPayload("http://www.a.dev")[0])
        assertEquals(0x05.toByte(), NdefMessage.uriPayload("tel:+100")[0])
        assertEquals(0x06.toByte(), NdefMessage.uriPayload("mailto:a@b.c")[0])
        assertEquals(0x00.toByte(), NdefMessage.uriPayload("geo:1,2")[0])
    }

    @Test
    fun prefixIsStrippedFromPayload() {
        val payload = NdefMessage.uriPayload("https://www.a.dev")
        assertEquals("a.dev", String(payload, 1, payload.size - 1, Charsets.UTF_8))
    }

    @Test
    fun uriRecordBytes() {
        // D1 01 06 55 04 'a' '.' 'd' 'e' 'v'
        assertArrayEquals(hex("D101065504612E646576"), NdefMessage.uriRecord("https://a.dev"))
    }

    @Test
    fun ndefFileHasBigEndianNlen() {
        val file = NdefMessage.ndefFile("https://a.dev")
        assertEquals(0x00.toByte(), file[0])
        assertEquals(10.toByte(), file[1])
        assertEquals(12, file.size)
    }

    @Test
    fun defaultUrlEncodes() {
        val file = NdefMessage.ndefFile(TagPrefs.DEFAULT_URL)
        val payloadLength = "obstalabs.dev".length + 1
        assertEquals(payloadLength, file[4].toInt() and 0xFF)
        assertEquals(2 + 4 + payloadLength, file.size)
    }

    @Test
    fun longestAllowedUrlFits() {
        val url = "https://" + "a".repeat(Type4Constants.MAX_NDEF_FILE_SIZE - 10)
        val file = NdefMessage.ndefFile(url)
        assertEquals(Type4Constants.MAX_NDEF_FILE_SIZE, file.size)
    }

    @Test(expected = NdefTooLargeException::class)
    fun tooLongUrlIsRejected() {
        NdefMessage.ndefFile("https://" + "a".repeat(Type4Constants.MAX_NDEF_FILE_SIZE - 9))
    }

    @Test
    fun ccFileBytes() {
        // CCLEN 000F, v2.0, MLe 003B, MLc 0034, TLV 04 06 E104, max size 0400, read 00, write FF
        assertArrayEquals(hex("000F20003B00340406E104040000FF"), Type4Constants.CC_FILE)
    }
}

class Type4TagTest {
    private val selectApp = hex("00A4040007D276000085010100")
    private val selectCc = hex("00A4000C02E103")
    private val selectNdef = hex("00A4000C02E104")
    private val ok = Type4Constants.SW_OK

    private var url: String? = "https://a.dev"
    private val tag = Type4Tag { url?.let { NdefMessage.ndefFile(it) } }

    @Test
    fun fullReadSequence() {
        assertArrayEquals(ok, tag.process(selectApp))
        assertArrayEquals(ok, tag.process(selectCc))
        assertArrayEquals(Type4Constants.CC_FILE + ok, tag.process(hex("00B000000F")))
        assertArrayEquals(ok, tag.process(selectNdef))
        assertArrayEquals(hex("000A") + ok, tag.process(hex("00B0000002")))
        assertArrayEquals(
            NdefMessage.uriRecord("https://a.dev") + ok,
            tag.process(hex("00B000020A")),
        )
    }

    @Test
    fun readIsTruncatedAtEndOfFile() {
        tag.process(selectApp)
        tag.process(selectNdef)
        assertArrayEquals(hex("6576") + ok, tag.process(hex("00B0000AFF")))
    }

    @Test
    fun readPastEndIsRejected() {
        tag.process(selectApp)
        tag.process(selectNdef)
        assertArrayEquals(Type4Constants.SW_WRONG_P1P2, tag.process(hex("00B0002001")))
    }

    @Test
    fun fileSelectBeforeAppSelectFails() {
        assertArrayEquals(Type4Constants.SW_FILE_NOT_FOUND, tag.process(selectCc))
    }

    @Test
    fun readWithoutFileSelectFails() {
        tag.process(selectApp)
        assertArrayEquals(Type4Constants.SW_FILE_NOT_FOUND, tag.process(hex("00B0000002")))
    }

    @Test
    fun disabledTagHidesApplication() {
        url = null
        assertArrayEquals(Type4Constants.SW_FILE_NOT_FOUND, tag.process(selectApp))
    }

    @Test
    fun urlChangeAppliesOnNextSelect() {
        tag.process(selectApp)
        url = "https://b.dev"
        tag.process(selectApp)
        tag.process(selectNdef)
        assertArrayEquals(
            NdefMessage.ndefFile("https://b.dev") + ok,
            tag.process(hex("00B00000FF")),
        )
    }

    @Test
    fun resetRequiresReselect() {
        tag.process(selectApp)
        tag.reset()
        assertArrayEquals(Type4Constants.SW_FILE_NOT_FOUND, tag.process(selectNdef))
    }

    @Test
    fun unknownInstructionAndClass() {
        assertArrayEquals(Type4Constants.SW_INS_NOT_SUPPORTED, tag.process(hex("00D6000000")))
        assertArrayEquals(Type4Constants.SW_CLA_NOT_SUPPORTED, tag.process(hex("80A4040000")))
    }

    @Test
    fun shortApduRejected() {
        assertArrayEquals(Type4Constants.SW_WRONG_P1P2, tag.process(hex("00A4")))
    }
}

// WO-12: completion observes a reader session without changing any APDU response bytes.
class Type4ReadCompletionTest {
    private val selectApp = hex("00A4040007D276000085010100")
    private val selectCc = hex("00A4000C02E103")
    private val selectNdef = hex("00A4000C02E104")
    private val file = NdefMessage.ndefFile("https://example.com")
    private var completions = 0
    private val tag = Type4Tag({ file }, { completions++ })

    @Test
    fun fullReadReportsOneCompletionWithUnchangedBytes() {
        assertArrayEquals(hex("9000"), tag.process(selectApp))
        assertArrayEquals(hex("9000"), tag.process(selectNdef))
        assertArrayEquals(file.copyOfRange(0, 2) + hex("9000"), tag.process(read(0, 2)))
        assertEquals(0, completions)
        assertArrayEquals(file.copyOfRange(2, file.size) + hex("9000"), tag.process(read(2, 255)))
        assertEquals(1, completions)
    }

    @Test
    fun ccOnlyDoesNotCompleteTheSession() {
        tag.process(selectApp)
        tag.process(selectCc)
        assertArrayEquals(Type4Constants.CC_FILE + hex("9000"), tag.process(read(0, 255)))
        assertEquals(0, completions)
    }

    @Test
    fun nlenOnlyDoesNotCompleteTheSession() {
        tag.process(selectApp)
        tag.process(selectNdef)
        tag.process(read(0, 2))
        assertEquals(0, completions)
    }

    @Test
    fun repeatedReadsAndApplicationSelectsCountOnceBeforeDeactivation() {
        tag.process(selectApp)
        tag.process(selectNdef)
        repeat(2) { tag.process(read(0, 255)) }
        tag.process(selectApp)
        tag.process(selectNdef)
        tag.process(read(0, 255))
        assertEquals(1, completions)
    }

    @Test
    fun resetAllowsTheNextSessionToComplete() {
        repeat(2) {
            tag.process(selectApp)
            tag.process(selectNdef)
            tag.process(read(0, 255))
            tag.reset()
        }
        assertEquals(2, completions)
    }

    @Test
    fun refusedApplicationCannotReportCompletion() {
        val unavailable = Type4Tag({ null }, { completions++ })
        assertArrayEquals(hex("6A82"), unavailable.process(selectApp))
        assertArrayEquals(hex("6A82"), unavailable.process(selectNdef))
        assertArrayEquals(hex("6A82"), unavailable.process(read(0, 255)))
        assertEquals(0, completions)
    }

    @Test
    fun emptyEofAndPastEndReadsDoNotReportCompletion() {
        tag.process(selectApp)
        tag.process(selectNdef)
        assertArrayEquals(hex("9000"), tag.process(read(file.size, 1)))
        assertArrayEquals(hex("6B00"), tag.process(read(file.size + 1, 1)))
        assertEquals(0, completions)
    }

    @Test
    fun aReadMustReachTheLastNdefByteToComplete() {
        tag.process(selectApp)
        tag.process(selectNdef)
        tag.process(read(2, 1))
        assertEquals(0, completions)
        assertArrayEquals(byteArrayOf(file.last()) + hex("9000"), tag.process(read(file.lastIndex, 1)))
        assertEquals(1, completions)
    }

    private fun read(offset: Int, length: Int): ByteArray = byteArrayOf(
        0, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), length.toByte(),
    )
}
