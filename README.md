# PocketTag

[![ci](https://github.com/ppiankov/pockettag/actions/workflows/ci.yml/badge.svg)](https://github.com/ppiankov/pockettag/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Android 7.0+](https://img.shields.io/badge/android-7.0%2B-green.svg)

Your phone is the business card: choose what to share, then tap another phone.

## What PocketTag is

A tiny Android app that makes the phone act like an NFC sticker carrying your selected
web link, contact card (vCard), WhatsApp chat, call, email, SMS link, text note, place,
Android app, or Wi-Fi network.
It emulates an NFC Forum Type 4 Tag through Android host card emulation (HCE) and serves
one NDEF record.
The other phone reads an ordinary NFC tag; how it handles the content depends on its apps
and NFC support. For links, the other phone needs nothing installed.

Keep several saved items and pick one to share. Everything lives in the app's private
storage on your phone. Contact details are entered manually.

## What PocketTag is NOT

- **Not a card service.** No profiles, no account, no subscription, no sign-up.
- **Not a profile host.** It sends a link; the page behind the link is yours to host.
- **Not a tag writer.** It does not program physical NFC stickers.
- **No cloud.** The app has no `INTERNET` permission and no third-party SDKs, analytics, or
  ads.
- **Not for iOS.** iOS does not let third-party apps emulate tags. iPhones can *read* the
  tag (see the tested-devices table).
- **Not new.** Phones emulating NDEF tags is prior art; see [Prior art](#prior-art).

## Philosophy

Paper cards run out at every conference. The technology to replace them has existed for
years, buried in apps with ads, accounts, or a card platform attached. PocketTag is the
smallest thing that does the job: one chosen item, a tap, nothing else. If it ever needs a server,
it has stopped being PocketTag.

## Quick start

```sh
git clone https://github.com/ppiankov/pockettag
cd pockettag
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35.0.0, a phone with NFC
and HCE support.

## Usage

1. Open PocketTag, tap **Add**, and choose a content type.
2. Enter a label and the fields for that type, then tap **Save**. Invalid or oversized items
   show an inline error and are not saved.
3. Tap a saved item to select it. **Press and hold** an item to edit it or delete it; there is
   no separate edit button.
4. Make sure NFC is on in system settings and **Serve tag** is switched on.
5. Unlock your phone and hold its back against the back of the other phone. The reader
   handles the selected content according to its NFC settings and installed apps.

Switch **Serve tag** off to stop answering readers. Selection and edits take effect on the
next tap. Deleting the selected item selects the first remaining item; deleting the last
item leaves **No item selected**, and readers see no tag.

Upgrading from v0.1 preserves the selected link. Web, call, email, and SMS links use their
matching types when the original link can be preserved exactly. Other values appear as
**Saved link**: you can select, edit, or delete them, but cannot add them directly. A fresh
installation starts with the same default web link.

Nonzero tap counts appear under each saved item; hold an item and tap **Reset count** to
clear its count.

Switch **Keep screen awake** on to prevent automatic screen timeout while PocketTag's main
screen is visible. It starts off and remembers your choice. Leaving the screen lets the
phone time out normally; the power button still locks it.

**Booth mode:** tap **Booth mode** to show the selected item's label and tap count full
screen. The screen stays on while booth mode is visible, warnings show when sharing is
unavailable, and **Sent at &lt;time&gt;** stays visible after each completed read, with seconds
in the phone's locale. The next read updates it; changing the item, resetting its count,
or leaving booth mode clears it. If NFC is off, the screen offers a button to open NFC
settings. Press Back to return to the item list.
In chip mode, the built-in tag answers taps without the app; booth mode cannot count those
reads or show **Sent at &lt;time&gt;** for them.

The service is registered without requiring an unlocked device; whether a given phone
answers taps while locked or with the screen off is up to its NFC stack and is recorded as
observed below, not promised. On the Huawei P40 the phone must be unlocked; PocketTag does
not need to be the app on screen.

## Chip mode

On phones with the supported NXP content API, an optional **Use the phone's built-in tag
(answers even when locked)** switch appears below **Serve tag**. It writes the selected
item into the NFC controller's built-in Type 4 tag. The controller answers without the
app; locked and powered-off behaviour depends on the phone. Observed results are recorded
in the table below. Other phones keep using HCE and show no chip-mode switch.

Switching **Serve tag** off is the global stop control, even when chip mode is off. The
app writes an empty NDEF record and reports **Off. The built-in tag is verified empty.**
only after an exact read-back. An unknown result keeps a warning and **Retry** visible;
turn NFC off if the phone may still be serving the previous item. Opening the app also
reconciles the chip with the current switches and selection, clearing stale content.

If an item does not fit the controller, the app attempts to empty it and reports whether
that was verified. There is no assumed controller capacity. The existing 1024-byte tag
file limit still applies to saved items.
For `STATUS_FAILED` or `ERROR_INVALID_LENGTH`, the failed-item warning includes the
attempted byte length and says the item is probably too large.

Switch on **Show last tap details** to see the last built-in tag write status and byte
length. For `ERROR_RF_ACTIVATED`, move the phones apart before tapping **Retry**.

On the Sony XQ-BC72, a 99-byte contact card and a 340-byte long web link published with
verified read-back. A 431-byte contact card and a 440-byte web link both returned
`STATUS_FAILED` and fell back to verified empty. Dummy ASCII HTTPS links narrowed the
boundary: 348 bytes of NDEF content wrote and verified; 349 bytes returned `STATUS_FAILED`
with verified empty afterward. Their tag files are 350 and 351 bytes including the
two-byte length field. This is a measured boundary for those links on this Sony.

**Before uninstalling, switch Serve tag off and wait for the verified-empty status.**
Uninstalling does not stop the built-in tag. Writing an empty record is not secure
erasure. Switching chip mode off does not change vendor routing or restore HCE on the
Sony Xperia XQ-BC72; a reader may still recognise an empty tag.

## Tested devices

| Tag (PocketTag) | Reader | Result |
|---|---|---|
| Huawei P40 (v0.1 URL) | Samsung Galaxy S25 | **works**: full Type 4 read, reader opens the URL |
| Huawei P40 (v0.2 web link) | Samsung Galaxy S25 | **works**: browser opens; a v0.1 URL carries over as the selected web link |
| Huawei P40 (v0.2 contact card) | Samsung Galaxy S25 | **works**: the contact is offered and added to the phone book |
| Huawei P40 (v0.2 WhatsApp chat) | Samsung Galaxy S25 | **works**: WhatsApp opens the chat |
| Huawei P40 (v0.2 call) | Samsung Galaxy S25 | **works**: dialer opens with the number |
| Huawei P40 (v0.2 email) | Samsung Galaxy S25 | **works**: mail app opens |
| Huawei P40 (v0.2 SMS) | Samsung Galaxy S25 | **works**: messaging app opens |
| Huawei P40 (text note) | Samsung Galaxy S25 | **works**: the tag viewer shows the note |
| Huawei P40 (text note) | iPhone 11 | no visible response on a flat, aligned retap; PocketTag's completed-read count increased |
| Huawei P40 (place) | Samsung Galaxy S25 | **works**: a map opens at the chosen landmark after a retap; the P40's Access Cards screen appeared on one attempt |
| Huawei P40 (place) | iPhone 11 | **works**: Google Maps opens |
| Huawei P40 (app, installed Calculator) | Samsung Galaxy S25 | **works**: Calculator opens |
| Huawei P40 (app, Firefox not installed) | Samsung Galaxy S25 | **works**: an app-store chooser appears, then Play Store offers installation |
| Huawei P40 (app) | iPhone 11 | no visible response; PocketTag's completed-read count increased |
| Huawei P40 (Wi-Fi) | Samsung Galaxy S25 | **works**: Connect offered and the test network joined after retries; the P40's Access Cards screen also appeared |
| Huawei P40 (Wi-Fi) | iPhone 11 | no visible response; a completed read was not confirmed |
| Huawei P40, screen locked | Samsung Galaxy S25 | no response: unlock the P40 first |
| Sony Xperia XQ-BC72 (Android 13, HCE) | Samsung Galaxy S25 | fails: reader reports "Empty tag" |
| Sony Xperia XQ-BC72 (Android 13, HCE) | Huawei phone | fails: reader reports "Empty tag" |
| Sony Xperia XQ-BC72 (chip mode, web link) | Samsung Galaxy S25 | **works**: website opens unlocked, locked, in Booth, and at the Sony boot logo |
| Sony Xperia XQ-BC72 (chip mode, 431-byte contact card) | Samsung Galaxy S25 | not published: `STATUS_FAILED`, fallback to verified empty; one tap showed a chooser (earlier reads reported "unknown tag type") |
| Sony Xperia XQ-BC72 (chip mode, 99-byte contact card) | Samsung Galaxy S25 | **works**: `WRITTEN`, verified read-back; contact import offered on one tap |
| Sony Xperia XQ-BC72 (chip mode, 340-byte long web link) | Samsung Galaxy S25 | **works**: `WRITTEN`, verified read-back; reader tried to open the URL on one tap |
| Sony Xperia XQ-BC72 (chip mode, 440-byte long web link) | Samsung Galaxy S25 | not published: `STATUS_FAILED`, fallback to verified empty; one tap showed a chooser |
| Sony Xperia XQ-BC72 (chip mode, 440-byte web link, size warning) | — | `STATUS_FAILED`, fallback to verified empty; the screen says the item is probably too large and now serves nothing |
| Sony Xperia XQ-BC72 (chip mode, 348-byte dummy web link) | — | `WRITTEN`, verified read-back; 350-byte tag file including the length field |
| Sony Xperia XQ-BC72 (chip mode, 349-byte dummy web link) | — | `STATUS_FAILED`, fallback to verified empty; 351-byte tag file including the length field |
| Sony Xperia XQ-BC72 (chip mode, normal web link restored) | — | `WRITTEN`, verified read-back; current status confirms the web link is serving |
| Sony Xperia XQ-BC72 (Serve tag off) | Samsung Galaxy S25 | built-in tag verified empty; reader reports "unknown tag type" |
| Sony Xperia XQ-BC72 (powered off, built-in tag empty) | Samsung Galaxy S25 | no response while fully off; reader reports "unknown tag type" at the boot logo |
| Samsung Galaxy S25 (PocketTag not on screen) | Huawei P40 | **works**, two taps: the S25 asks whether its built-in "Embedded Tag" or PocketTag should answer |
| Huawei P40 (booth mode, contact card) | Samsung Galaxy S25 | **works**: the S25 reads the whole contact card and the tap count goes up; Sent time persists after separation and updates on the next read. Changing the item and reopening Booth clears it |
| Huawei P40 (booth mode, past 30-second screen timeout) | Samsung Galaxy S25 | **works**: P40 stays awake past the timeout and the selected email is offered on a tap; leaving Booth restores automatic screen timeout |
| Huawei P40 (booth mode, warnings) | — | NFC off shows the warning and settings button; NFC on clears both without exiting Booth (observed on screen). Serve off shows Paused after opening Booth; Serve on clears it after reopening. Deleting the selected spare selects the first remaining item; this procedure could not reach No item selected while other items remained |
| Samsung Galaxy S25 (booth mode, web link) | Huawei P40 | **works**: one tap opens the web link; Sent appears and the tap count goes up |
| Samsung Galaxy S25 (main screen, web link) | Huawei P40 | **works**: one tap opens the web link |
| Samsung Galaxy S25 | Sony Xperia XQ-BC72 | not yet tested |
| Huawei P40 (v0.2) | iPhone 16, iOS 26.6.1 (background tag reading) | **works** for web link, WhatsApp, call, email, SMS; contact card: no response |

The Sony failure is below the app. Its NFC controller has a built-in Type 4 tag (an NXP
"T4T NFCEE"), switched on in the vendor configuration (`NXP_T4T_NFCEE_ENABLE=0x01` in
`/vendor/etc/libnfc-nxp.conf`). Android's NFC stack routes the NDEF AID `D2760000850101` to that
built-in tag (NFCEE `0x10`) instead of the host, even though its own routing summary lists the
AID under the host and PocketTag. The NCI snoop log confirms it: on every tap the controller
reports `RF_NFCEE_ACTION_NTF` for NFCEE `0x10` with the NDEF AID, and the built-in tag answers
with its empty NDEF file. That is why the phone read as "Empty tag" before PocketTag was
installed, and why no command ever reaches the app, locked or unlocked. Changing the routing
needs a modified vendor partition (root), so no host card emulation app can serve a tag on this
phone. What does work, from a regular app, is writing the message into that built-in tag through
NXP's vendor library; an S25 then opens the written URL. PocketTag now offers this as
opt-in chip mode, with verified read-back and Serve tag as the global stop control.

With **Show last tap details** switched on, a successful tap's trace shows the reader
selecting the NDEF application, reading the Capability Container, selecting the NDEF file,
reading its length, then reading the selected item's record.

## Architecture

```
reader phone ──APDU──▶ Android NFC stack ──▶ NdefHostApduService ──▶ Type4Tag
                                                   │                    │
                                           ItemStore (active)     NdefMessage (encoder)
```

- `TagItem.kt`: pure Kotlin content types, input validation, URI generation, and vCard 3.0
  encoding (UTF-8, escaped values, CRLF endings, no line folding).
- `NdefMessage.kt`: pure Kotlin URI, Text, MIME, and external-type record encoders, using short records through
  255 payload bytes and four-byte lengths beyond that. The NDEF file adds a two-byte NLEN;
  the Capability Container advertises a 1024-byte maximum. `Type4Tag` is the APDU state
  machine: SELECT application → SELECT CC → READ BINARY → SELECT NDEF → READ BINARY.
- `ItemStore.kt`: saved items as JSON in the existing private `pockettag` preferences
  (`items_v1` and `active_item_id`). Pure `ItemJson` and `ItemState` handle mapping,
  migration, and selection. Unreadable JSON entries are retained unchanged in stored
  order but are not displayed or served. The legacy URL is retained; serving and trace
  settings stay in the same preferences file.
- `NdefHostApduService.kt`: hands APDUs to `Type4Tag` and snapshots the active item's NDEF
  file on each application SELECT. Disabled serving, no selection, or an encoding error
  returns `6A82` (application not found). Wi-Fi READ BINARY replies are recorded only as
  byte counts, before logging or saving the tap trace.
- `MainActivity.kt` and `EditItemActivity.kt`: selector and type-specific editor, built
  only from framework widgets.
- `res/xml/apduservice.xml`: registers the NDEF application AID `D2760000850101`.

The tag is read-only: the CC file denies write access.

Permissions: CI runs `aapt dump permissions` on every build and fails if
`android.permission.INTERNET` appears. The only permission the app requests is
`android.permission.NFC`. Output for 0.2.0:

```
package: dev.ppiankov.pockettag
uses-permission: name='android.permission.NFC'
```

## Known limitations

- The phone must be unlocked when booth mode starts. It keeps the screen on only while
  the booth screen is visible.
- On the Huawei P40 with Huawei Wallet, about 3 in 4 contact-card taps with a captured
  `ON_UNLOCKED` screen state completed with PocketTag on screen (7/9) and in the background
  (10/13). Misses often coincided with Wallet's tap screen. Watch the tap count or **Sent**
  and tap again if a completed read is not confirmed. **Booth mode** keeps the screen awake
  while visible, avoiding the separate locked-screen failure. See the
  [Huawei tap findings](docs/devices/huawei-p40.md).
- Tap counts measure completed NFC reads, not distinct people, and are stored only on the phone.
- The whole tag file is limited to 1024 bytes: two bytes of NLEN plus at most 1022 bytes
  of NDEF message, including record headers. Large contact cards or messages may not fit.
- iPhone background tag reading does not act on contact cards. Reader behaviour varies
  by content type and installed apps; see the tested-devices table.
- Text notes use UTF-8 NFC Forum Text records with an editable language tag (English by
  default). They can contain multiple lines. In the iPhone 11 check, no visible response
  appeared despite PocketTag recording completed reads.
- Places open a Google Maps link with coordinates rounded to at most six decimals. Enter
  coordinates or paste a Google Maps `@lat,lng` or `?q=lat,lng` link; other link forms are
  not supported. PocketTag does not request your location or provide directions.
- App items use an Android Application Record: the reader opens the installed app or its
  Play Store page. Enter the package name after `id=` in its store URL.
  In the iPhone 11 check, no visible response appeared despite PocketTag recording completed reads.
- Wi-Fi items are for Android readers that support WSC tags. The iPhone 11 check showed no
  visible response; a completed read was not confirmed.
  SSIDs are limited to 32 UTF-8 bytes and personal-network passwords to 8–63 printable
  ASCII characters. WPA3 personal is shared as WPA2-PSK for transition-mode networks;
  enterprise networks are not supported. The password is stored in the app's private
  preferences on the phone, and is omitted from the tap trace.
  Anyone whose phone taps this one receives the network password, including while this
  phone is locked in chip mode; choose a guest network.
- If another installed app also registers the NDEF AID, Android may ask which one to use.
- Two phones both in reader mode will not see each other; the phone running PocketTag
  must be the one being read.
- Phones whose NFC controller has its own built-in Type 4 tag can answer readers themselves,
  so PocketTag never sees the request; the Sony Xperia XQ-BC72 is one. Results per device are
  in the table above. If a tap fails, switch on **Show last tap details**: the trace shows whether any
  command arrived. The trace stays on the phone and can include bytes of the selected content.
- On the Sony XQ-BC72, a 431-byte contact card and a 440-byte web link did not publish to
  the built-in tag and fell back to verified empty. Dummy web links wrote and verified at
  348 bytes of NDEF content and were rejected at 349; see Chip mode above.
- On the Galaxy S25 the first tap shows a chooser between Samsung's "Embedded Tag" and
  PocketTag unless PocketTag is on screen (main screen or Booth mode), where one tap was
  observed.
- WhatsApp shows its own safety warning when the chat number is not in the reader's
  contacts. That is WhatsApp's behaviour, not PocketTag's.

## Roadmap

- Record results for the device pairs in the table.
- Show the selected item as a QR code for phones without NFC.

## Prior art

The mechanism is well known. PocketTag was written after studying these projects:

- [LuigiVampa92/ndef-emulator](https://github.com/LuigiVampa92/ndef-emulator) (Apache-2.0):
  Type 4 NDEF emulation over `HostApduService` in Kotlin.
- NfcHceNdefEmulator, the commercial Touch Knot, and ad-supported emulators on Google Play.

PocketTag's code is a small independent implementation of the NFC Forum Type 4 Tag
specification; it does not bundle any of the above.

Built with AI agents under a Hiveram work-order record (4 work orders).

## License

[Apache-2.0](LICENSE)
