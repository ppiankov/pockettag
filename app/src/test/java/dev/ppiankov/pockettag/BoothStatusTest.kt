package dev.ppiankov.pockettag

import org.junit.Assert.assertEquals
import org.junit.Test

// WO-15: every booth warning has a deterministic condition and priority.
class BoothStatusTest {
    @Test fun readyWhenEveryConditionIsMet() {
        assertEquals(BoothStatus.READY, status())
    }

    @Test fun unreadableItemsBlockSharing() {
        assertEquals(BoothStatus.UNREADABLE, status(readable = false))
    }

    @Test fun missingSelectionBlocksSharing() {
        assertEquals(BoothStatus.NO_ITEM, status(hasItem = false))
    }

    @Test fun missingNfcBlocksSharing() {
        assertEquals(BoothStatus.NO_NFC, status(nfcAvailable = false))
    }

    @Test fun missingHceBlocksSharing() {
        assertEquals(BoothStatus.NO_HCE, status(hceAvailable = false))
    }

    @Test fun disabledNfcBlocksSharing() {
        assertEquals(BoothStatus.NFC_OFF, status(nfcEnabled = false))
    }

    @Test fun disabledServingPausesSharing() {
        assertEquals(BoothStatus.PAUSED, status(serving = false))
    }

    @Test fun encodingFailureBlocksSharing() {
        assertEquals(BoothStatus.ITEM_INVALID, status(itemEncodes = false))
    }

    @Test fun firstWarningWinsWithEveryCombinationOfLaterFailures() {
        val priority = listOf(BoothStatus.UNREADABLE, BoothStatus.NO_ITEM, BoothStatus.NO_NFC,
            BoothStatus.NO_HCE, BoothStatus.NFC_OFF, BoothStatus.PAUSED, BoothStatus.ITEM_INVALID)
        priority.forEachIndexed { firstFailure, expected ->
            val remainingConditions = priority.size - firstFailure - 1
            repeat(1 shl remainingConditions) { combination ->
                val conditions = BooleanArray(priority.size) { index ->
                    index < firstFailure || (index > firstFailure &&
                        combination and (1 shl (index - firstFailure - 1)) != 0)
                }
                assertEquals("first failure $firstFailure, later conditions $combination", expected,
                    boothStatus(conditions[0], conditions[1], conditions[2], conditions[3],
                        conditions[4], conditions[5], conditions[6]))
            }
        }
    }

    // WO-15: named fixture inputs isolate each failure while other conditions stay ready.
    private fun status(
        readable: Boolean = true,
        hasItem: Boolean = true,
        nfcAvailable: Boolean = true,
        hceAvailable: Boolean = true,
        nfcEnabled: Boolean = true,
        serving: Boolean = true,
        itemEncodes: Boolean = true,
    ) = boothStatus(readable, hasItem, nfcAvailable, hceAvailable, nfcEnabled, serving, itemEncodes)
}
