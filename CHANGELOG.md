# Changelog

The record of the pre-release builds. The release workflow writes an
entry for every release it publishes, from the product commits since
the previous release, and the same record rides the release page, so
the file needs no hand care. Every version carries its own entry, so
a second release of the same day adds a line and replaces nothing.

Every entry lands as a bot commit on the default branch, so pull the
branch before you push work that follows a release.

Nightlies published before this file existed carry their notes on the
release page alone.

<!-- The release workflow inserts every new entry below this line. -->

## Nightly 2026-11-22 — versionCode 6, versionName 2026.11.22

Built from `8be2284f0ef9858bae745111001c5f8a248e3ba7`.
APK SHA-256: `9a3fe3df0213f49c2b1fa50f882f4c271232a3decc722d42f8cd7ae359a6463a`.

Commits since the previous nightly (`4c6e572dda1b1d083b227e08e8579b4d8634aabd..8be2284f0ef9858bae745111001c5f8a248e3ba7`):

- `8be2284` Show the installed media apps at open, and move the list controls to the cards
- `66caf45` Give the stop the circle the transport buttons wear
- `e1c493a` Keep scratch screenshots out of version control
- `0c13a75` Start the stream on the second pick, and stop it on the picked row
- `e8400ff` Drop the local network permission until the target SDK rises
- `42d136c` Fold the BDD suite into journeys and shared-premise scenarios
- `69d0828` Open the app from a session row tap, the way a discovered row does
- `7de428b` Route the picked destination mid-stream, and leave the old player to the server
- `b8e89e6` Fold the technical details away, and open them on a tap
- `a5d715d` Give the empty-screen scenario a phone that holds no session
- `7883387` Join the track line from the fields that hold text
- `b7d3e9e` Drop the legacy pairing card, and tell a pairing failure in a snackbar
- `8cddbef` Show the Music Assistant server as one row in the Destination card
- `098de57` Show the session row when the app starts to play, not when it names its track

## Nightly 2026-11-21 — versionCode 5, versionName 2026.11.21

Built from `4c6e572dda1b1d083b227e08e8579b4d8634aabd`.
APK SHA-256: `c20839457e9e9b4cf8d24335af29c83bbd41455dcd6db5ec08786809bb83702c`.

Commits since the previous nightly (`28ebe3ee87caf31f3f5e09b92c084173b9c8f7bd..4c6e572dda1b1d083b227e08e8579b4d8634aabd`):

- `4c6e572` Record every nightly release in a changelog
- `26ae0e9` Swap the plus for the Video Search glyph on the finder
- `460ce83` Open the app from a tap on the whole discovered row
- `0fb8961` Open the notification access screen of the app directly
- `5dbf2e2` Open the app info page from the access prompt

