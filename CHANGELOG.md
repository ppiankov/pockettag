# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
