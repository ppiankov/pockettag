# Huawei P40 background NFC taps

On this Huawei P40, PocketTag completed 10 of 13 eligible background contact-card taps
(76.9%) and 7 of 9 eligible taps with its normal main screen open (77.8%). Eligibility
requires a captured `screen_state=ON_UNLOCKED`. Wallet started during all three eligible
background misses, but also during one completed foreground read. The exact cause remains
unproven, and no Wallet setting was verified to fix it.

## Setup

- Dates: 2026-10-03 through 2026-10-05, UTC.
- Tag: Huawei P40 (Android 10), installed PocketTag debug build reported as commit
  `2f71f7b`. The installed build's identity was not independently verified.
- Reader: Samsung Galaxy S25. Its Android version was not recorded.
- Content: the same selected contact card, with NFC and **Serve tag** on.
- Default NFC payment app: Huawei Wallet. The operator reported alternatives consisting
  of SIM cards and three bank apps; PocketTag was absent from that list.
- Wallet showed **Pay**, **Access cards**, and **Cards**; the operator described its
  default as **Access cards**. The Wallet version was not recorded.
- No Wallet or payment-default settings were changed.

## Method and limits

The baseline requested 20 physical taps with PocketTag behind the Android launcher. The
control requested 10 with PocketTag's normal main screen visible, rather than Booth mode.
The operator held the phones together for about two seconds. Later instructions explicitly
required separating them after that hold and keeping the P40 awake until capture completed.
Each slot was retained even if capture failed; failed captures were not replaced.

For each capture, the PocketTag trace header and completed-read counter were compared before
and after the tap. **Reached** means the trace's first line changed. **Complete** means the
last captured READ BINARY reached the file's end: offset plus response length equalled
NLEN plus two. A success requires both reached and complete. Counter changes measure reads,
not distinct people. Logcat supplied Wallet SwipeActivity startup and RF field-on counts;
`dumpsys nfc` supplied screen state, preferred service, and routing fields.

The NFC snapshot was taken after the operator's acknowledgement and a further two-second
delay. It is a post-tap observation, not a measurement at first field contact. Some replies
were delayed and the phone auto-locked; the operator confirmed that this happened. Only
the complete `ON_UNLOCKED` value is admitted to rates, cross-tabs, and routing comparisons.
Other captured states are listed separately as **excluded (screen not unlocked)**, never
as routing failures. Missing captures are **unverified** and have no inferred screen state.

The operator confirmed that baseline taps 17 and 18 began on the Android launcher. Before
foreground tap 9, a setup check found PocketTag's main screen visible and unlocked; the
operator subsequently confirmed that it was unlocked immediately before the tap, but did
not explicitly confirm the visible app at that instant. Setup checks and later snapshots
cannot establish the order of Wallet startup, controller changes, and reader commands.
These small batches on different days do not establish that either mode improves reliability.

Only derived fields and short reader outcomes are published. Contact contents, response
bytes, raw preferences, device identifiers, and raw logs are omitted.

## Results

| Condition | Physical attempts | Valid captures | Eligible | Excluded | Unverified | Complete reads | Wallet starts |
|---|---:|---:|---:|---:|---:|---|---|
| `baseline` | 20 | 19 | 13 | 6 | 1 | 10/13 (76.9%) | 3/13 (23.1%) |
| `wallet-popup-off` | Not run | — | — | — | — | — | — |
| `payment-default-changed` | Not run | — | — | — | — | — | — |
| `foreground-main` | 10 | 9 | 9 | 0 | 1 | 7/9 (77.8%) | 2/9 (22.2%) |

Cross-tab of Wallet startup against PocketTag reached, using eligible captures only:

| Condition | Wallet started | Reached: yes | Reached: no |
|---|---|---:|---:|
| `baseline` | Yes | 0 | 3 |
| `baseline` | No | 10 | 0 |
| `foreground-main` | Yes | 1 | 1 |
| `foreground-main` | No | 6 | 1 |

Every reached eligible capture also completed the read. A Samsung chooser alone does not
establish failure: several captures completed after a chooser appeared.

### Eligible baseline taps

`Δ count` is the completed-read counter change. `RF on` is the number of logged
RF_FIELD_ON_DETECTED events in the capture window, not a count of physical taps.

| Tap | screen_state | Reached | Complete | Δ count | Wallet started | RF on | S25 outcome |
|---:|---|---|---|---:|---|---:|---|
| 2 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 3 | ON_UNLOCKED | No | N/A | 0 | Yes | 1 | Chooser appeared |
| 4 | ON_UNLOCKED | No | N/A | 0 | Yes | 1 | Chooser appeared |
| 5 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 6 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 7 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 8 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 9 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 12 | ON_UNLOCKED | No | N/A | 0 | Yes | 1 | Chooser appeared |
| 17 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 18 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Chooser, then contact offered |
| 19 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Chooser, then contact offered |
| 20 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered |

### Eligible foreground taps

| Tap | screen_state | Reached | Complete | Δ count | Wallet started | RF on | S25 outcome |
|---:|---|---|---|---:|---|---:|---|
| 2 | ON_UNLOCKED | No | N/A | 0 | No | 0 | Chooser appeared |
| 3 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Chooser, then contact offered |
| 4 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered immediately |
| 5 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered immediately |
| 6 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered immediately |
| 7 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered immediately |
| 8 | ON_UNLOCKED | Yes | Yes | 1 | No | 0 | Contact offered immediately |
| 9 | ON_UNLOCKED | No | N/A | 0 | Yes | 1 | Chooser appeared |
| 10 | ON_UNLOCKED | Yes | Yes | 1 | Yes | 1 | Contact offered immediately |

The P40 displayed Access cards on both foreground taps 9 and 10. Tap 9 did not change the
PocketTag trace; tap 10 changed it and completed the read. Foreground tap 2 did not change
the trace and had no logged Wallet startup. Its before/after saved trace command counts
were both nine, and no new PocketTag commands appeared in its captured log.

### Excluded: screen not unlocked

These diagnostics are retained separately and do not contribute to any rate, cross-tab,
or routing comparison. They are not classified as routing failures.

| Baseline tap | screen_state | Reached | Complete | Δ count | Wallet started | RF on | S25 outcome |
|---:|---|---|---|---:|---|---:|---|
| 1 | ON_LOCKED | Yes | Yes | 23 | No | 1 | Contact offered |
| 10 | OFF_LOCKED | Yes | Yes | 1 | No | 0 | Chooser, then contact offered |
| 11 | OFF_LOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 14 | OFF_LOCKED | Yes | Yes | 1 | Yes | 1 | Chooser, then contact offered |
| 15 | OFF_LOCKED | Yes | Yes | 1 | No | 0 | Contact offered |
| 16 | OFF_LOCKED | Yes | Yes | 1 | No | 0 | Chooser, then contact offered |

Baseline tap 1's counter delta of 23 is preserved; it does not establish 23 physical taps
or people. Post-tap locked states alongside completed reads do not establish locked-at-contact
support. The no-tap dry run had `OFF_LOCKED`, an unchanged trace, and zero counter change;
it is outside all experimental totals.

### Unverified physical attempts

| Condition | Tap | Capture gap | Operator outcome |
|---|---:|---|---|
| `baseline` | 13 | USB debugging became unauthorized after the physical tap; no valid capture | Same chooser result as tap 12 |
| `foreground-main` | 1 | Selected item changed during capture; the capture guard stopped before saving diagnostics | Reported that the read worked |

Neither attempt is counted as a routing failure or a measured success. The reason the
selection changed during foreground tap 1 remains unexplained.

## Post-tap routing comparison

All eligible snapshots within each condition had the same recorded route fields:

| Condition | Read result | Captures | Preferred foreground service | Default route | NDEF AID route |
|---|---|---:|---|---|---|
| `baseline` | Complete | 10 | `null` | Secure element | `0x0` |
| `baseline` | Not reached | 3 | `null` | Secure element | `0x0` |
| `foreground-main` | Complete | 7 | PocketTag | Secure element | `0x0` |
| `foreground-main` | Not reached | 2 | PocketTag | Secure element | `0x0` |

Here PocketTag is
`dev.ppiankov.pockettag/dev.ppiankov.pockettag.NdefHostApduService`, and the NDEF AID is
`D2760000850101`. The dumps placed that AID under route `0x0` in both successful and
unsuccessful captures. A secure-element default route alone does not show where the NDEF
SELECT went. Android documents AID-specific routing and a separate default for unmatched
AIDs. These later snapshots cannot expose a transient field-on change or confirm the
controller's actual exchange. [Android HCE routing documentation](https://developer.android.com/develop/connectivity/nfc/hce#coexistence-with-secure-element-cards).

## Wallet controls

**Reader-popup switch: not found; no exact setting name or menu path was obtained.** The
operator checked the Wallet UI and reported no such option. Consequently `wallet-popup-off`
was not run. This is a result for this installation, not a claim that every Huawei Wallet
version lacks the option. Huawei documents that another NFC device can open Wallet's Swipe
screen; its article does not name a switch for suppressing that reader-triggered popup.
[Huawei Swipe-screen explanation](https://consumer.huawei.com/nz/support/content/en-us15785296/).

**Payment-default control: not run, operator declined.** Huawei Wallet remained selected.
Huawei documents the EMUI 10 path as **Settings > More connections > NFC > Default app**;
that is a separate control from Wallet's card view.
[Huawei default-app instructions](https://consumer.huawei.com/ae-en/support/content/en-us15785525/).
PocketTag registers its AID in category `other`, rather than `payment`; its absence from
the payment-app list does not establish a missing NFC registration.
[Android HCE categories](https://developer.android.com/develop/connectivity/nfc/hce#aid-groups-and-categories).

No setting was changed, so no restoration was needed. No verified setting-based fix is
available from this experiment.

## Hypotheses

| Hypothesis | Verdict | Evidence and limit |
|---|---|---|
| H1: Wallet's field-on startup reconfigures routing before the NDEF SELECT | Association supported; routing race inconclusive | All 3 eligible background misses started Wallet; all 10 completed baseline reads did not. Foreground tap 9 missed with Wallet, tap 10 completed with Wallet, and tap 2 missed without Wallet. Startup is neither necessary nor sufficient for a miss across both conditions. Post-tap routes do not establish event order or transient reconfiguration. |
| H2: the S25 selects payment/PPSE first and the controller stays with the secure element | Inconclusive | Every eligible snapshot had a secure-element default and the NDEF AID under `0x0`, including completed reads. No reader-side APDU sequence or controller-level NCI trace was collected to test payment-first selection or persistence. |
| H3: background controller power or idle state causes misses | Inconclusive | There were 3 misses among 13 unlocked baseline captures and 2 among 9 unlocked foreground captures. Background operation alone cannot explain every miss, but controller power and idle transitions were not measured. The 6 non-unlocked captures are excluded, not used as failures supporting this hypothesis. |

## Recommendation

Use **Booth mode** for events to keep PocketTag visible and the screen awake. Both the
normal main screen and Booth mode request foreground service preference, and Booth mode
also keeps the screen on while visible. Android describes foreground preference as a way
to prefer an HCE service while its activity is foreground.
[Android foreground preference](https://developer.android.com/develop/connectivity/nfc/hce#foreground-service-preference).
This experiment tested the normal main screen, not a repeated Booth-mode batch; it does
not establish a Booth-mode success rate or guarantee that Booth mode prevents Wallet
interference. Confirm the completed-read counter or **Sent** indication after each tap.

No app-side routing fix is justified by these snapshots. A follow-up investigation could
record field-time reader commands and controller events to distinguish the Wallet race,
payment-first selection, and power hypotheses. A separate controlled Booth-mode batch would
measure its reliability directly. Changing the payment default remains an optional control
that was declined here; no benefit is claimed for it. Further testing or an app change
requires a separate decision.
