package dev.ppiankov.pockettag

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.w3c.dom.Element

// WO-20: pin the displayed warning for every status so unrelated failures cannot imply size.
@RunWith(Parameterized::class)
internal class ChipSizeStatusTest(
    private val status: ChipWriteStatus, // WO-20: each status has its own test case.
    private val expectedMessage: String, // WO-20: the full warning is part of the user-visible contract.
) {
    @Test fun statusUsesOnlyItsPermittedWarning() {
        assertEquals(ChipWriteStatus.entries.toSet(), cases().map { it[0] }.toSet())
        val outcome = ChipOutcome(status, ChipWriteStatus.WRITTEN, FAILED_LENGTH, true, true, true)
        val file = File("src/main/res/values/strings.xml").takeIf { it.isFile }
            ?: File("app/src/main/res/values/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        val original = (0 until strings.length).map { strings.item(it) as Element }
            .single { it.getAttribute("name") == "chip_item_failed" }.textContent.replace("\\'", "'")
        val plurals = document.getElementsByTagName("plurals")
        val group = (0 until plurals.length).map { plurals.item(it) as Element }
            .single { it.getAttribute("name") == "chip_item_failed_size" }
        val items = group.getElementsByTagName("item")
        val template = (0 until items.length).map { items.item(it) as Element }
            .single { it.getAttribute("quantity") == "other" }.textContent.replace("\\'", "'")
        val message = chipSizeFailureMessage(ChipDisplay.ITEM_FAILED, outcome) { length ->
            String.format(Locale.ROOT, template, length)
        } ?: original
        assertEquals(expectedMessage, message)
    }

    companion object {
        private const val FAILED_LENGTH = 431 // WO-20: reuse the measured Sony rejection length.
        private const val ORIGINAL = "The phone's built-in tag could not hold this item; it now serves nothing." // WO-20: retain the existing warning for unrelated failures.
        private const val SIZE = "The phone's built-in tag could not hold this item (431 bytes); " +
            "it is probably too large. It now serves nothing." // WO-20: pin the size wording for the two allowed codes.

        // WO-20: explicit rows prevent a new vendor status from silently receiving size guidance.
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> = listOf(
            arrayOf(ChipWriteStatus.WRITTEN, ORIGINAL),
            arrayOf(ChipWriteStatus.COUNT_MISMATCH, ORIGINAL),
            arrayOf(ChipWriteStatus.UNKNOWN, ORIGINAL),
            arrayOf(ChipWriteStatus.STATUS_FAILED, SIZE),
            arrayOf(ChipWriteStatus.ERROR_RF_ACTIVATED, ORIGINAL),
            arrayOf(ChipWriteStatus.ERROR_MPOS_ON, ORIGINAL),
            arrayOf(ChipWriteStatus.ERROR_NFC_NOT_ON, ORIGINAL),
            arrayOf(ChipWriteStatus.ERROR_INVALID_FILE_ID, ORIGINAL),
            arrayOf(ChipWriteStatus.ERROR_INVALID_LENGTH, SIZE),
            arrayOf(ChipWriteStatus.ERROR_CONNECTION_FAILED, ORIGINAL),
            arrayOf(ChipWriteStatus.ERROR_EMPTY_PAYLOAD, ORIGINAL),
            arrayOf(ChipWriteStatus.ERROR_NDEF_VALIDATION_FAILED, ORIGINAL),
            arrayOf(ChipWriteStatus.ERROR_WRITE_PERMISSION, ORIGINAL),
            arrayOf(ChipWriteStatus.ERROR_NFC_OFF_TRIGGERED, ORIGINAL),
        )
    }
}
