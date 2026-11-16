# Media control from the sender

A capability beside the capture that rides on the same access: the sender
can drive the media apps themselves, per app, starting with play and pause.
The phone runs proved it with the Spotify podcast. This plan records what
the platform offers, what the PoC added, and what a real control surface
needs.

## What the platform offers

The same controllers that publish the metadata hand out transport control.
`MediaController.transportControls` exposes the standard set:

| Command | Note |
| --- | --- |
| `play`, `pause`, `stop` | the basics the PoC wired |
| `seekTo` | milliseconds on the session timeline |
| `skipToNext`, `skipToPrevious` | queue moves, where the app allows |
| `fastForward`, `rewind` | the app's own step size |
| `setPlaybackSpeed` | for the apps that expose speed |

Beyond the standard set, each session publishes custom actions the app
defined itself. The probe rounds saw them plainly: Spotify ran "Seek 15
seconds back/forward", "Change playback speed", and "Add to collection";
Tidal ran "Shuffle", "Repeat", and "Remove from My Collection". The
`PlaybackState` carries an `actions` bitmask that names exactly which
standard commands the app honours at this moment. The probe values for all
three apps included the play and pause bits.

No new permission is involved. The notification listener grant that makes
the sessions readable also authorizes sending commands; target calls go
straight to the addressed session and bypass the media button priority
entirely, so pausing Spotify does not touch Tidal.

## What the PoC added

Both files are uncommitted, beside the capture PoC:

1. `PerAppCapture` keeps the live `MediaController` of every listed
   session and exposes `play(packageName)` and `pause(packageName)`, each
   forwarding to `transportControls`. The controllers refresh with the
   list.
2. The picker rows carry Play and Pause buttons. A press sends the command
   and schedules a re-read one and a half seconds later, so the row state
   and the now-playing line update themselves.

## What the phone run proved

The demo ran against the Spotify podcast, session paused at 2:12:

1. Play from our row: dumpsys read `state=PLAYING`, speed 1.0, and the
   episode was audible.
2. Pause from our row: dumpsys read `state=PAUSED`, position held at 3:38.
   Twenty-five seconds of playback sat between the two presses.

One quirk surfaced: Spotify had sat paused for about half an hour, and
around the first command it dropped its session entirely and left the
picker list. The command was a silent no-op, not an error; relaunching the
app rebuilt the session and the buttons worked first try. The rule for any
reader: a stored controller can go stale at any moment, so every command
must tolerate an app-side session teardown.

## Behaviour facts a real control surface must keep

1. **Gate on the bitmask.** Enable only the commands the session's actions
   bits allow. The custom actions list is the same honesty: render one
   button per `CustomAction` the state publishes.
2. **Targets are per session, priority never changes.** The media button
   session of the system stays untouched; a control row addresses exactly
   one package.
3. **Live radio pauses as a stop.** BBC Sounds pauses by ending the stream;
   play resumes the station slot, and the position is the slot offset, not
   a timeline. Podcast and music sessions behave as expected.
4. **The UI list must re-read after commands.** The PoC re-reads on a timer
   after each press; the real design should subscribe to
   `OnActiveSessionsChangedListener` and the controller callbacks instead.

## Graduation notes

- Stable row identity by package name, so layout does not jump as sessions
  come and go.
- The control row belongs in the final picker design together with the
  idle-app listing; both ride on the same controllers.
- Skip, seek, and speed are the highest-value next commands for podcast
  use, since the Spotify session publishes all three either as standard
  actions or as custom actions.
