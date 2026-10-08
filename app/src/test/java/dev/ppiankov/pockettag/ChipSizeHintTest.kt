package dev.ppiankov.pockettag

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

// WO-20: size guidance follows completed vendor outcomes without changing chip verification.
class ChipSizeHintTest {
    // WO-20: format the actual UI resource so the warning and attempted length agree.
    @Test fun failedItemNamesItsSizeAsTheLikelyReason() {
        assertEquals("The phone's built-in tag could not hold this item (431 bytes); " +
            "it is probably too large. It now serves nothing.",
            message(ChipDisplay.ITEM_FAILED, failed(ChipWriteStatus.STATUS_FAILED)))
    }

    // WO-20: RF recovery keeps its existing hint and Retry without a guessed size cause.
    @Test fun rfFailureKeepsRecoveryWithoutSizeWording() {
        val outcome = failed(ChipWriteStatus.ERROR_RF_ACTIVATED)
        assertNull(message(ChipDisplay.ITEM_FAILED, outcome))
        assertTrue(chipRfHint(ChipDisplay.ITEM_FAILED, outcome))
        assertTrue(chipRetryAvailable(ChipDisplay.ITEM_FAILED, outcome))
    }

    // WO-20: unknown results and count mismatches do not establish a vendor size failure.
    @Test fun inconclusiveOutcomesDoNotClaimSize() {
        for (status in listOf(ChipWriteStatus.UNKNOWN, ChipWriteStatus.COUNT_MISMATCH)) {
            assertNull(message(ChipDisplay.ITEM_FAILED, failed(status)))
        }
        assertNull(message(ChipDisplay.ITEM_FAILED, null))
    }

    // WO-20: other vendor failures retain their warning; only invalid length also warrants size guidance.
    @Test fun otherVendorFailuresUseTheAttemptedLength() {
        for (status in listOf(ChipWriteStatus.ERROR_MPOS_ON, ChipWriteStatus.ERROR_NFC_NOT_ON,
            ChipWriteStatus.ERROR_INVALID_FILE_ID, ChipWriteStatus.ERROR_INVALID_LENGTH,
            ChipWriteStatus.ERROR_CONNECTION_FAILED, ChipWriteStatus.ERROR_EMPTY_PAYLOAD,
            ChipWriteStatus.ERROR_NDEF_VALIDATION_FAILED, ChipWriteStatus.ERROR_WRITE_PERMISSION,
            ChipWriteStatus.ERROR_NFC_OFF_TRIGGERED)) {
            val text = message(ChipDisplay.ITEM_FAILED, failed(status))
            if (status == ChipWriteStatus.ERROR_INVALID_LENGTH) assertTrue(text!!.contains("431 bytes"))
            else assertNull(text)
        }
    }

    // WO-20: pending, unknown, and verified displays cannot inherit a previous failure's size suggestion.
    @Test fun otherDisplaysNeverShowSizeFailureText() {
        for (display in ChipDisplay.entries.filterNot { it == ChipDisplay.ITEM_FAILED }) {
            assertNull(message(display, failed(ChipWriteStatus.STATUS_FAILED)))
        }
        assertNull(message(null, failed(ChipWriteStatus.STATUS_FAILED)))
    }

    // WO-20: successful proof and non-vendor read-back failures retain the original wording.
    @Test fun writtenOutcomesNeverClaimSize() {
        assertNull(message(ChipDisplay.ITEM_FAILED,
            ChipOutcome(ChipWriteStatus.WRITTEN, null, FAILED_LENGTH, true, false, true)))
        assertNull(message(ChipDisplay.ITEM_FAILED, failed(ChipWriteStatus.WRITTEN)))
    }

    // WO-20: the byte count uses the resource's singular form and rejects invalid metadata.
    @Test fun sizeWordingHasCorrectUnits() {
        assertTrue(message(ChipDisplay.ITEM_FAILED, failed(ChipWriteStatus.STATUS_FAILED, 1))!!
            .contains("(1 byte)"))
        assertNull(message(ChipDisplay.ITEM_FAILED, failed(ChipWriteStatus.STATUS_FAILED, 0)))
    }

    // WO-20: guidance adds no content fields to the persisted diagnostic outcome.
    @Test fun outcomeRemainsContentFree() {
        assertFalse(ChipOutcome::class.java.declaredFields.any {
            it.type == ByteArray::class.java || it.type == String::class.java
        })
    }

    private fun failed(status: ChipWriteStatus, length: Int = FAILED_LENGTH) =
        ChipOutcome(status, ChipWriteStatus.WRITTEN, length, true, true, true)

    // WO-20: JVM tests read the shipped plural templates without an Android resource runtime.
    private fun message(display: ChipDisplay?, outcome: ChipOutcome?): String? =
        chipSizeFailureMessage(display, outcome) { length ->
            val file = File("src/main/res/values/strings.xml").takeIf { it.isFile }
                ?: File("app/src/main/res/values/strings.xml")
            val plurals = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("plurals")
            val group = (0 until plurals.length).map { plurals.item(it) as Element }
                .single { it.getAttribute("name") == "chip_item_failed_size" }
            val items = group.getElementsByTagName("item")
            val quantity = if (length == 1) "one" else "other"
            val template = (0 until items.length).map { items.item(it) as Element }
                .single { it.getAttribute("quantity") == quantity }.textContent.replace("\\'", "'")
            String.format(Locale.ROOT, template, length)
        }

    private companion object {
        const val FAILED_LENGTH = 431 // WO-20: the rejected Sony card provides the size-guidance fixture.
    }
}
