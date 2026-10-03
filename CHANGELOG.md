# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Per-item counts of completed NFC reads, with a reset action and local-only storage.
- Booth mode with a full-screen sharing display, screen-on handling, readiness warnings,
  and brief feedback after completed reads.

## [0.2.0] - 2026-10-03

### Added

- Saved web links, contact cards, WhatsApp chats, call links, emails, and SMS links, with
  one selected item shared on each tap.
- A framework item selector and type-specific editor with input validation and confirmed
  deletion; deleting the selected item picks the first remaining item.
- Private JSON storage with exact-link migration into a matching content type or an
  editable Saved link that preserves otherwise unsupported values.
- Deterministic tests for content encoding, JSON mapping, migration, selection, and large
  Type 4 reads.

### Changed

- NDEF files now support up to 1024 bytes, including the two-byte length prefix, with long
  URI and MIME records when payloads exceed 255 bytes.
- HCE serves the selected item and hides the tag when no item is selected or encoding fails.
- Unreadable saved entries are preserved unchanged through edits to other items.
- Pasted URLs, addresses, and numbers are trimmed without changing free-form text.
- Serve tag changes only the on/off setting, leaving the original saved URL untouched.

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
