package dev.ppiankov.pockettag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// WO-18: exercise read observations without wall-clock timing or an Android activity.
class BoothSentTest {
    @Test fun visibleReadRecordsItsTime() {
        assertEquals(2000L, boothSentAt("one", 3, "one", 4, true, null, 2000L))
    }

    @Test fun unchangedCountKeepsConfirmationAfterSeparation() {
        assertEquals(2000L, boothSentAt("one", 4, "one", 4, true, 2000L, 9000L))
    }

    @Test fun nextReadUpdatesConfirmation() {
        assertEquals(9000L, boothSentAt("one", 4, "one", 5, true, 2000L, 9000L))
    }

    @Test fun selectionChangeClearsEvenWhenTheNewCountIsHigher() {
        assertNull(boothSentAt("one", 4, "two", 8, true, 2000L, 9000L))
    }

    @Test fun countResetClearsConfirmation() {
        assertNull(boothSentAt("one", 4, "one", 0, true, 2000L, 9000L))
    }

    @Test fun resumeEstablishesBaselineWithoutConfirmingHiddenReads() {
        assertNull(boothSentAt("one", 4, "one", 8, false, 2000L, 9000L))
    }

    @Test fun missingSelectionNeverConfirmsARead() {
        assertNull(boothSentAt(null, 0, null, 1, true, null, 9000L))
    }
}
