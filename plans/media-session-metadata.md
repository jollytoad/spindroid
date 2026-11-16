# Media-session metadata from the phone: findings and how SendSpin can use them

Date: 2026-11-16. Device: Nokia XR20, Android 14. Probes read live `MediaSession` metadata
through `MediaSessionManager.getActiveSessions` with our existing
`PlayingAppsListener` notification-access grant. A throwaway probe APK
(sources in `.tmp/metadataprobe/`) enumerated every key of `MediaMetadata`
for three sessions: Tidal playing a music track, BBC Sounds playing a
podcast, and BBC Sounds playing live radio.

## What the apps publish

### Tidal (com.aspiro.tidal), track "Belfast" by Orbital — 14 fields

| Key | Value | Type |
|---|---|---|
| `METADATA_KEY_TITLE` | `Belfast` | text |
| `METADATA_KEY_ARTIST` | `Orbital` | text |
| `METADATA_KEY_ALBUM` | `Orbital` | text |
| `METADATA_KEY_DURATION` | `491000` | long, ms |
| `METADATA_KEY_MEDIA_ID` | `TRACK::82530771` | text |
| `METADATA_KEY_ART` | bitmap 544x544 | bitmap |
| `METADATA_KEY_ART_URI` | `https://resources.tidal.com/images/.../640x640.jpg` | text URL |
| `METADATA_KEY_IS_EXPLICIT` | `-1` | long |
| `isFavorite` | `false` | Tidal extra |
| `isDolbyAtmos` | `false` | Tidal extra |
| `sourceId` | opaque hex id | Tidal extra |
| `KEY_IMMERSIVE_AUDIO` (androidx.car.app) | `-1` | extra |
| `KEY_CONTENT_FORMAT_TINTABLE_LARGE_ICON_URI` (androidx.car.app) | present, opaque | extra |
| `KEY_CONTENT_FORMAT_TINTABLE_SMALL_ICON_URI` (androidx.car.app) | present, opaque | extra |

### BBC Sounds podcast, "No Such Thing As A Fish", episode "No Such Thing As Lithuanian Soap Ornaments" — 10 fields

| Key | Value | Type |
|---|---|---|
| `METADATA_KEY_TITLE` | `No Such Thing As A Fish` | text: programme name |
| `METADATA_KEY_DISPLAY_TITLE` | `No Such Thing As A Fish` | text |
| `METADATA_KEY_ARTIST` | `No Such Thing As Lithuanian Soap Ornaments` | text: episode name |
| `METADATA_KEY_DISPLAY_SUBTITLE` | `No Such Thing As Lithuanian Soap Ornaments` | text |
| `METADATA_KEY_DURATION` | `3417000` | long, ms |
| `METADATA_KEY_ART` | bitmap 680x680 | bitmap |
| `METADATA_KEY_ART_URI` | `https://ichef.bbci.co.uk/images/ic/688x688/p0pfgzh3.jpg` | text URL |
| `IS_LIVE` | `false` | long flag |
| `STATION` | `Sounds` | text: mode marker |
| `DOWNLOAD_STATUS` | present (0) | long flag |

### BBC Sounds live radio, Radio 2 playing "Too Cute To Be Lonely" by Holly Humberstone — 10 fields, same key set

| Key | Value | Type |
|---|---|---|
| `TITLE` / `DISPLAY_TITLE` | `Too Cute To Be Lonely` | text: now-playing track |
| `ARTIST` / `DISPLAY_SUBTITLE` | `Holly Humberstone` | text: now-playing artist |
| `STATION` | `Radio 2` | text: station name |
| `IS_LIVE` | `true` | long flag |
| `DURATION` | `9000000` | long: placeholder, not a real length |
| `ART` | bitmap 680x680 | bitmap |
| `ART_URI` | BBC image URL | text URL |
| `DOWNLOAD_STATUS`, `STATION` | as above | extras |

### Spotify free (com.spotify.music), music and podcast — 18 fields, adverts included

Spotify publishes the richest session of the three apps, and the extra keys
answer questions the others leave open. The key set is the same 18 keys for
music, podcast, and advert playback.

One key is new and important: `com.spotify.music.extra.CONTEXT_URI`, which
names the list the playback came from. Tidal and BBC Sounds publish nothing
like it.

| Key | Music: "hate that i made you love me" | Podcast: "S13 EP33" | Advert: "Matilda The Musical" |
| --- | --- | --- | --- |
| `METADATA_KEY_TITLE` | track title | episode title | advert name |
| `METADATA_KEY_ARTIST` | artist | show name | `Advertisement • 1 of 3` |
| `METADATA_KEY_ALBUM` | album | show name (empty on ads) | empty |
| `METADATA_KEY_MEDIA_ID` | `spotify:track:<id>` | `spotify:episode:<id>` | `spotify:ad:<id>` |
| `CONTEXT_URI` (Spotify) | `spotify:playlist:<id>` | `spotify:show:<id>` | show URI retained |
| `CONTEXT_TITLE` (Spotify) | "Olivia Dean Radio" | "Parenting Hell..." | show title retained |
| `CONTEXT_SHARE_URL` (Spotify) | open.spotify.com/playlist/... | open.spotify.com/show/... | retained |
| `METADATA_KEY_DURATION` | real (197950 ms) | real (2450301 ms) | break length (51288 ms) |
| `METADATA_KEY_ADVERTISEMENT` | `0` | `0` | **`1`** |
| `METADATA_KEY_ALBUM_ART` | bitmap 300x300 | bitmap 680x680 | bitmap 640x640 |
| `ALBUM_ART_URI`, `ART_HTTPS_URI` | content URI plus CDN URL | same | same, CDN URL |
| `IS_EXPLICIT` | 0 or 1 | 1 | present |
| `IS_19_PLUS`, `DOWNLOAD_STATUS` | 0, present | 0, present | present |
| `CONTEXT_DESCRIPTION` | empty | show description text | show description retained |
| extracted-colour key | hex colour | hex colour | `#121212` |

### What the Spotify advert probe proved

An audio advert inside a podcast ran end to end under the probe, and the
session handles it as a first-class now-playing item:

1. The advert arrives as its own item: `MEDIA_ID = spotify:ad:<hex>`,
   title = the campaign name ("Matilda The Musical", then "Post Office"),
   `DURATION` = the whole ad break (51288 ms for three slots).
2. `ARTIST` reads `Advertisement • 1 of 3`, which names the slot position in
   the break without any parsing.
3. `com.spotify.music.extra.ADVERTISEMENT` flips to `1` for the advert and
   back to `0` when the episode resumes. The flag is boolean long.
4. The context keys survive the advert: `CONTEXT_URI` and `CONTEXT_TITLE`
   stay on the show, so "what am I actually listening to" survives an ad
   break without extra state in the reader.
5. An advert can therefore be detected three independent ways: the
   `ADVERTISEMENT` flag, a `MEDIA_ID` under `spotify:ad:`, or an `ARTIST`
   starting with `Advertisement`. A reader that filters on the flag can
   ignore advert metadata cleanly.

### What the Spotify context solves for transfer

`CONTEXT_URI` is the fact the Tidal session lacks. A transfer can now know
the playing list itself, not just the current track:

- Podcast: `spotify:show:<id>` plus the episode id gives the exact episode
  and its show, with no text search.
- Music radio or playlist: `spotify:playlist:<id>` names the source list.
  Music Assistant holds no Spotify provider for playback, so the URI is
  still only metadata; the transfer path stays the search-and-queue design
  in [media-transfer plan](media-transfer-to-music-assistant.md).
- Music Assistant's search still maps titles to its own providers; the
  Spotify ids sharpen nothing on the MA side today beyond confidence.

## Cross-app observations

1. Neither BBC Sounds session exposes `MEDIA_ID`, `ALBUM`, queue items, or any
   provider URI. Only Tidal gives a machine-readable item id
   (`TRACK::82530771`). Spotify goes further and publishes both ids and the
   context list.
2. The mode marker is readable without parsing: `IS_LIVE` true/false and
   `STATION` (`Sounds` for on-demand podcast/audio, the station name for live
   radio).
3. Live radio rotates metadata as the station plays; the fields update and
   `DURATION` is meaningless (a fixed placeholder).
4. BBC reuses `ARTIST` for the episode name on podcasts. A UI must take
   `DISPLAY_TITLE`/`DISPLAY_SUBTITLE`, not `TITLE`/`ARTIST`, to label things
   correctly.
5. Every session carries a usable cover image, both inline bitmap and a
   full-size URL. The URL is the cheap pick for a UI.
6. Idle apps publish `metadata: null`. Read metadata only while a session
   reports `STATE_PLAYING` or `STATE_PAUSED`.
7. `MediaMetadata` has no generic getter. Enumerating needs the typed
   accessors (`getText`, `getLong`, `getBitmap`, `getRating`), so a reader
   handles key families one by one.

## How SendSpin could take this in

### A. Now-playing display in the per-app capture picker

The picker (`PerAppCaptureSection`) lists "TIDAL, com.aspiro.tidal". With the
controllers it already holds, each row can add the playing line:
"TIDAL — Belfast, Orbital" or "BBC Sounds — Radio 2 — Too Cute To Be Lonely".
Read `DISPLAY_TITLE` + `DISPLAY_SUBTITLE` for labels, and refresh through the
existing `refresh` button (optionally a `MediaController.Callback` for
track changes).

### B. Now-playing line in the send notification and StatusSection

`SendSpinService` passes the selected packages at capture start. The chosen
app's metadata becomes a one-line "Sending: Belfast — Orbital" in the
foreground notification and the capture status card. Live radio updates
themselves if we register a callback; otherwise refresh on the status tick.

### C. Metadata forwarding to Music Assistant

The sender already reads MediaSession metadata the same way MA's own
integrations do. If MA displays nothing for a captured app, forwarding is
possible in the source path: announce the playing item to MA when it changes.
The exact channel (session metadata on the SendSpin connection or an MA API
call) is a design decision for the delayed-speaker work, not a blocker.

## Searching and playing directly on Music Assistant

What the MA side offers, based on the public API surface:

1. Search: the websocket command `music/search` runs a global search across
   installed providers (Tidal and BBC Sounds are both supported). The
   `music_assistant-client` result includes typed items per media type plus
   provider URIs.
2. Resolve or verify: `music/item_by_uri` turns a URI into a full media item,
   and confirms a URI is valid before queueing.
3. Play: `player_queues/play_media` starts media on one player's queue:
   `{queue_id, media: [uri], option: "replay|replace|play"}`, with
   `radio_mode` optional. For live radio, the FAQ documents that the
   station media id can be passed directly as `media` to `play_media`.

### A concrete flow for "play what my phone is playing, on MA"

1. Sender reads the phone's media session: title, artist, `IS_LIVE`, `STATION`.
2. On-demand (music): send `music/search` with "TITLE ARTIST" and filter hits
   to the matching provider. With Tidal the match is deterministic up to a
   lookup: the session's `MEDIA_ID` is `TRACK::82530771`, and the Tidal
   provider exposes the same numeric id, so the sender can construct or
   confirm the provider URI instead of guessing from text.
3. Live radio: search the `STATION` value ("Radio 2") in MA, or hold a
   static table of BBC Sounds station ids. This sidesteps the fact that
   BBC Sounds exposes no ids at all.
4. Podcast: search episode and programme text. Episode ids are not exposed
   by the session, so search is the only path; ambiguity (same programme,
   many episodes) is resolved by the user or by duration matching
   (`DURATION` 3417000 ms).
5. Call `player_queues/play_media` on the chosen player (the one the sender
   is attached to, or any group).

### Honest limits

- For Tidal the session id gives a direct, no-guessing lookup.
- For BBC Sounds there is no id in the session, so both live and podcast
  play-through go through search text or static station ids. Live radio
  search by station name should be reliable; podcast episode search is
  best-effort.
- MA URI scheme across providers (`provider://...` shape) exists but the
  exact scheme strings should be confirmed against the live server in the
  spike; `music/item_by_uri` is the verification tool.
- The `music-assistant-client` library in this repo covers pairing, players,
  select-source, and setup today. Search and play_media need new commands;
  they are small additions on the existing WebSocket session.

## Probe mechanics, for repetition

The probe now lives in the repo at `tools/metadata-probe/`, with build and
run scripts and a README. The tool wraps everything this section described,
so future rounds are one command:

```sh
tools/metadata-probe/run.sh                 # first adb device
tools/metadata-probe/run.sh -s <serial>     # a named device
```

The script installs, grants the listener access
(`com.sendspin.probe/.ProbeListener`), starts the activity, and prints one
`package=` and `keys=` block per active session.

Device fact that shaped the probe: the Nokia blocks service start for an
app in the stopped state, which is why the probe has a launcher activity —
launching it once un-stops the package. The uninstall at the end of a round
also removes the grant, so a later round grants it again.
