package dev.ppiankov.pockettag

// WO-15: booth warnings share the selector's priority before checking the encoded item.
enum class BoothStatus { READY, UNREADABLE, NO_ITEM, NO_NFC, NO_HCE, NFC_OFF, PAUSED, ITEM_INVALID }

// WO-15: keep readiness independent of Android so every warning and its precedence is testable.
fun boothStatus(
    readable: Boolean,
    hasItem: Boolean,
    nfcAvailable: Boolean,
    hceAvailable: Boolean,
    nfcEnabled: Boolean,
    serving: Boolean,
    itemEncodes: Boolean,
): BoothStatus = when {
    !readable -> BoothStatus.UNREADABLE
    !hasItem -> BoothStatus.NO_ITEM
    !nfcAvailable -> BoothStatus.NO_NFC
    !hceAvailable -> BoothStatus.NO_HCE
    !nfcEnabled -> BoothStatus.NFC_OFF
    !serving -> BoothStatus.PAUSED
    !itemEncodes -> BoothStatus.ITEM_INVALID
    else -> BoothStatus.READY
}
