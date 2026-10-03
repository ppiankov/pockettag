# PocketTag

[![ci](https://github.com/ppiankov/pockettag/actions/workflows/ci.yml/badge.svg)](https://github.com/ppiankov/pockettag/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Android 7.0+](https://img.shields.io/badge/android-7.0%2B-green.svg)

Your phone is the business card: choose what to share, then tap another phone.

## What PocketTag is

A tiny Android app that makes the phone act like an NFC sticker carrying your selected
web link, contact card (vCard), WhatsApp chat, call, email, or SMS link. It emulates an NFC
Forum Type 4 Tag through Android host card emulation (HCE) and serves one NDEF record.
The other phone reads an ordinary NFC tag; how it handles the content depends on its apps
and NFC support.

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
3. Tap a saved item to select it. Hold an item to edit it or confirm its deletion.
4. Make sure NFC is on in system settings and **Serve tag** is switched on.
5. Unlock your phone and hold its back against the back of the other phone. The reader
   handles the selected content according to its NFC settings and installed apps.

Switch **Serve tag** off to stop answering readers. Selection and edits take effect on the
next tap. Deleting the selected item selects the first remaining item; deleting the last
item leaves **No item selected**, and readers see no tag.

On upgrading from v0.1, the existing URL becomes the selected **Web link** item. A fresh
installation starts with the same default web link.

The service is registered without requiring an unlocked device; whether a given phone
answers taps while locked or with the screen off is up to its NFC stack and is recorded as
observed below, not promised.

## Tested devices

These are the existing URL results. Checks of all six content types from the P40 to the
S25 are pending operator review; the reviewer will record those results here.

| Tag (PocketTag) | Reader | Result |
|---|---|---|
| Huawei P40 | Samsung Galaxy S25 | **works**: full Type 4 read, reader opens the URL |
| Sony Xperia XQ-BC72 (Android 13) | Samsung Galaxy S25 | fails: reader reports "Empty tag" |
| Sony Xperia XQ-BC72 (Android 13) | Huawei phone | fails: reader reports "Empty tag" |
| Samsung Galaxy S25 | Huawei P40 | **works**, two taps: the S25 asks which tag service to use on the first tap |
| Samsung Galaxy S25 | Sony Xperia XQ-BC72 | not yet tested |
| any | iPhone (background tag reading) | not yet tested |

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
NXP's vendor library; an S25 then opens the written URL. PocketTag does not do this yet: it is
planned as an opt-in mode (see the roadmap), because the built-in tag answers without the app,
including when the phone is locked.

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
- `NdefMessage.kt`: pure Kotlin URI and MIME record encoders, using short records through
  255 payload bytes and four-byte lengths beyond that. The NDEF file adds a two-byte NLEN;
  the Capability Container advertises a 1024-byte maximum. `Type4Tag` is the APDU state
  machine: SELECT application → SELECT CC → READ BINARY → SELECT NDEF → READ BINARY.
- `ItemStore.kt`: saved items as JSON in the existing private `pockettag` preferences
  (`items_v1` and `active_item_id`). Pure `ItemJson` and `ItemState` handle mapping,
  migration, and selection. The legacy URL is retained; serving and trace settings stay
  in the same preferences file.
- `NdefHostApduService.kt`: hands APDUs to `Type4Tag` and snapshots the active item's NDEF
  file on each application SELECT. Disabled serving, no selection, or an encoding error
  returns `6A82` (application not found).
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

- The whole tag file is limited to 1024 bytes: two bytes of NLEN plus at most 1022 bytes
  of NDEF message, including record headers. Large contact cards or messages may not fit.
- iPhone background tag reading does not act on contact cards. Reader behaviour varies
  by content type and installed apps; the additional types await device verification.
- If another installed app also registers the NDEF AID, Android may ask which one to use.
- Two phones both in reader mode will not see each other; the phone running PocketTag
  must be the one being read.
- Phones whose NFC controller has its own built-in Type 4 tag can answer readers themselves,
  so PocketTag never sees the request; the Sony Xperia XQ-BC72 is one. Results per device are
  in the table above. If a tap fails, switch on **Show last tap details**: the trace shows whether any
  command arrived. The trace stays on the phone and can include bytes of the selected content.
- On the Galaxy S25 the first tap shows a chooser for which service should answer, so a read
  takes two taps.

## Roadmap

- Record results for the device pairs in the table.
- Opt-in chip mode for phones like the Sony above: write the selected item into the NFC
  controller's built-in tag.
- Show the selected item as a QR code for phones without NFC.

## Prior art

The mechanism is well known. PocketTag was written after studying these projects:

- [LuigiVampa92/ndef-emulator](https://github.com/LuigiVampa92/ndef-emulator) (Apache-2.0):
  Type 4 NDEF emulation over `HostApduService` in Kotlin.
- NfcHceNdefEmulator, the commercial Touch Knot, and ad-supported emulators on Google Play.

PocketTag's code is a small independent implementation of the NFC Forum Type 4 Tag
specification; it does not bundle any of the above.

Built with AI agents under a Hiveram work-order record (1 work order).

## License

[Apache-2.0](LICENSE)
