# PocketTag

[![ci](https://github.com/ppiankov/pockettag/actions/workflows/ci.yml/badge.svg)](https://github.com/ppiankov/pockettag/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Android 7.0+](https://img.shields.io/badge/android-7.0%2B-green.svg)

Your phone is the business card: tap it against someone else's phone and their phone opens your URL.

## What PocketTag is

A tiny Android app that makes the phone act like an NFC sticker carrying one URL. It
emulates an NFC Forum Type 4 Tag through Android host card emulation (HCE) and serves a
single NDEF URI record. The other phone needs nothing installed: it sees an ordinary URL
tag, exactly as if you had tapped a sticker.

One screen, one URL, one switch. The URL lives in the app's private storage on your phone.

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
smallest thing that does the job: a URL, a tap, nothing else. If it ever needs a server,
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

1. Open PocketTag, type your URL, tap **Save**.
2. Make sure NFC is on in system settings and **Serve tag** is switched on.
3. Unlock your phone and hold its back against the back of the other phone.
4. The other phone shows a notification or opens the URL, depending on its NFC settings.

Switch **Serve tag** off to stop answering readers. Changing the URL takes effect on the
next tap.

The service is registered without requiring an unlocked device; whether a given phone
answers taps while locked or with the screen off is up to its NFC stack and is recorded as
observed below, not promised.

## Tested devices

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
reading its length, then reading the URI record.

## Architecture

```
reader phone ──APDU──▶ Android NFC stack ──▶ NdefHostApduService ──▶ Type4Tag
                                                   │                    │
                                              TagPrefs (URL)      NdefMessage (encoder)
```

- `NdefMessage.kt`: pure Kotlin, no Android imports. It encodes the URI record (with the
  NFC Forum URI prefix abbreviation), the NDEF file (2-byte NLEN + message), and the
  Capability Container. `Type4Tag` is the APDU state machine: SELECT application →
  SELECT CC → READ BINARY → SELECT NDEF → READ BINARY.
- `NdefHostApduService.kt`: a thin `HostApduService` that hands APDUs to `Type4Tag` and
  rebuilds the NDEF file from preferences on each application SELECT.
- `MainActivity.kt`: the settings screen, built only from framework widgets.
- `res/xml/apduservice.xml`: registers the NDEF application AID `D2760000850101`.

The tag is read-only: the CC file denies write access.

Permissions: CI runs `aapt dump permissions` on every build and fails if
`android.permission.INTERNET` appears. The only permission the app requests is
`android.permission.NFC`. Output for 0.1.0:

```
package: dev.ppiankov.pockettag
uses-permission: name='android.permission.NFC'
```

## Known limitations

- URLs are limited to 255 bytes after prefix compression (one short NDEF record).
- If another installed app also registers the NDEF AID, Android may ask which one to use.
- Two phones both in reader mode will not see each other; the phone running PocketTag
  must be the one being read.
- Phones whose NFC controller has its own built-in Type 4 tag can answer readers themselves,
  so PocketTag never sees the request; the Sony Xperia XQ-BC72 is one. Results per device are
  in the table above. If a tap fails, switch on **Show last tap details**: the trace shows whether any
  command arrived. The trace stays on the phone and holds only the URL you chose.
- On the Galaxy S25 the first tap shows a chooser for which service should answer, so a read
  takes two taps.

## Roadmap

- Record results for the device pairs in the table.
- Several saved items to choose from: web link, contact card (vCard), WhatsApp chat, call,
  email, SMS.
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
