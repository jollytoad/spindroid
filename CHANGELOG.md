# Changelog

The record of the nightly releases. The nightly workflow writes an
entry for every release it publishes, from the product commits since
the previous nightly, and the same record rides the release page, so
the file needs no hand care. A forced rerun on the same day replaces
the release, and the entry of that day with it.

Every entry lands as a bot commit on the default branch, so pull the
branch before you push work that follows a nightly.

Nightlies published before this file existed carry their notes on the
release page alone.

<!-- The nightly workflow inserts every new entry below this line. -->

## Nightly 2026-11-21 — versionCode 5, versionName 2026.11.21

Built from `4c6e572dda1b1d083b227e08e8579b4d8634aabd`.
APK SHA-256: `c20839457e9e9b4cf8d24335af29c83bbd41455dcd6db5ec08786809bb83702c`.

Commits since the previous nightly (`28ebe3ee87caf31f3f5e09b92c084173b9c8f7bd..4c6e572dda1b1d083b227e08e8579b4d8634aabd`):

- `4c6e572` Record every nightly release in a changelog
- `26ae0e9` Swap the plus for the Video Search glyph on the finder
- `460ce83` Open the app from a tap on the whole discovered row
- `0fb8961` Open the notification access screen of the app directly
- `5dbf2e2` Open the app info page from the access prompt

