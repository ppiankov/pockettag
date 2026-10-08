# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Saved multiline UTF-8 text notes with an editable language tag, shared as NFC Forum
  Text records.
- Per-item counts of completed NFC reads, with a reset action and local-only storage.
- Booth mode with a full-screen sharing display, screen-on handling, readiness warnings,
  and brief feedback after completed reads.
- Opt-in built-in tag mode on supported NXP phones, with exact read-back verification,
  an EMPTY fallback for failed items, persistent unknown-state warnings, and Retry.
- Local built-in tag write diagnostics with vendor status names, byte lengths,
  verification results, and RF-active recovery guidance.

### Changed

- Failed built-in tag writes explain the attempted byte length as a likely size issue
  for completed non-RF vendor failures.
- Booth confirmation now keeps the last completed read's local time, including seconds,
  visible until the item changes, its count is reset, or booth mode is left.
- Serve tag off also empties the built-in tag, independently of the chip-mode setting;
  app startup reconciles stale content. Booth mode identifies chip reads as uncounted.
- Built-in tag checks show "Checking the built-in tag…" while pending; warnings and Retry
  appear only after a failed check.
- Built-in tag capability detection runs once on the background worker so it cannot
  block the screen; early requests retain the current goal and startup reconciliation.
- Recorded built-in tag and Booth device results, including Sony contact-card failure
  and Samsung web-link reads from Booth and the main screen.

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
