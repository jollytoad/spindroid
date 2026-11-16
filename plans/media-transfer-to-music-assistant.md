# Media transfer to Music Assistant

A future scenario beside the audio capture: read what plays on the phone from
its media sessions, and move it onto a Music Assistant player or group. This
plan states what the sessions expose, what can be transferred, and what
cannot.

The raw probe results for every metadata field are in
[media-session-metadata.md](media-session-metadata.md). This file covers only
what transfer needs from them.

## What the phone sessions expose

Two probe sessions on the device: Tidal playing a music track, and BBC Sounds
playing a podcast and live radio.

| Fact | Tidal | BBC Sounds |
| --- | --- | --- |
| Track id | `MEDIA_ID = TRACK::82530771` | none |
| Now playing | title, artist, album, duration | title, subtitle, duration |
| Play queue | empty, `size=0` while playing | empty |
| MediaBrowserService | yes, published | not probed |
| Mode marker | none | `IS_LIVE` flag, `STATION` text |
| Cover art | bitmap plus full-size URL | bitmap plus URL |

## What a transfer can and cannot do

| Ask | Verdict | How |
| --- | --- | --- |
| The current track | yes, exact | The `MEDIA_ID` carries Tidal's numeric track id; the Tidal provider in Music Assistant takes the same id. |
| The whole album of the current track | yes, by derivation | Resolve the track in Music Assistant, read the album from the track item, and queue the album URI. The album is grounded in Tidal's own data model, not in a session guess. |
| The playlist the track came from | Spotify: yes. Tidal and BBC Sounds: no | Spotify publishes `CONTEXT_URI`, the URI of the playing playlist or show. Tidal and BBC Sounds carry no source list. |
| The phone queue's future order | no | Tidal publishes no queue. Only the track that plays now is visible. |
| Follow the phone as tracks advance | partial | Sample each `MEDIA_ID` as it changes and append. It transfers what plays, never what will play. |

The mode marker makes live radio transfer usable: the session names
the station, and a search for that name on the BBC Sounds provider gives the
station.

## How the transfer runs on Music Assistant

The command surface is the WebSocket API of the server. The commands:

1. `music/search` - a global search across installed providers. Takes text,
   returns media items with provider URIs.
2. `music/item_by_uri` - resolves one URI to a full media item. Use it to
   verify a URI before queueing, and to read the album of a track.
3. `player_queues/play_media` - starts media on one player queue. The payload
   is `{queue_id, media: [uri], option: "replay|replace|play"}`, and
   `radio_mode` is optional.

A track with a known id skips the search: build or confirm the URI and check
it with `music/item_by_uri`.

## The user-facing choice

Because playlist and future queue are invisible, the transfer control asks
the user which expansion they want. Three honest options:

1. **This track** - play the one track now.
2. **Whole album** - replace the queue with the album.
3. **Radio from here** - queue the track with `radio_mode`, so Music
   Assistant continues in its own way.

Live radio transfers the station, not the songs. Podcasts transfer by text
search, confirmed against the known duration when one episode of many fits.

## What the client library needs

`music-assistant-client` covers pairing, players, select-source, and setup
today. Search and play need two new commands on the existing WebSocket
session.

## Open spike questions

1. Does a third-party `MediaBrowser` connection to Tidal's
   `MediaBrowserService` work, or does the check for Android Auto block it?
   Allowed access would strengthen browsing, but it still cannot name the
   playing playlist.
2. Does a tidal track item on the live server carry its album with a URI, and
   does `player_queues/play_media` accept an album URI? One
   `music/item_by_uri` call and one queue command settle it.
3. Does the BBC Sounds provider search return stations by name reliably? The
   probe showed only the station title, so search is the only path.

A later probe found that Spotify exposes `CONTEXT_URI`, so playlist context
is available per app and no rule covers every app. See
[media-session-metadata.md](media-session-metadata.md) for the Spotify
session.
