# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-10-03

### Added

- Saved web links, contact cards, WhatsApp chats, call links, emails, and SMS links, with
  one selected item shared on each tap.
- A framework item selector and type-specific editor with input validation and confirmed
  deletion; deleting the selected item picks the first remaining item.
- Private JSON storage with migration of the existing URL into a selected Web link item.
- Deterministic tests for content encoding, JSON mapping, migration, selection, and large
  Type 4 reads.

### Changed

- NDEF files now support up to 1024 bytes, including the two-byte length prefix, with long
  URI and MIME records when payloads exceed 255 bytes.
- HCE serves the selected item and hides the tag when no item is selected or encoding fails.

## [0.1.0] - 2026-10-03

### Added

- NFC Forum Type 4 Tag emulation over Android host card emulation (AID `D2760000850101`),
  serving one read-only NDEF URI record.
- One settings screen: editable URL (stored on the device), on/off switch, NFC status line.
- URL changes take effect on the next tap without restarting the app.
- Trace of the last reader exchange (shown behind a "Show last tap details" switch, off by
  default) and an AID routing check, so a failed tap can be diagnosed without a cable.
- PocketTag claims the tag AID as preferred service while it is open.
- Unit tests for the NDEF encoder (URI prefix byte, length fields, CC file) and the APDU
  state machine.
- CI: unit tests, debug build, and a check that the APK requests no `INTERNET` permission.
