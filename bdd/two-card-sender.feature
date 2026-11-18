@two-card
Feature: The two-card sender screen

  The primary screen of SpinDroid is two always-present cards.
  The Source card lists the apps that hold a media session, and the
  Destination card lists the players the network offers. Connect
  streams the picked source to the picked destination. A plus beside
  the Source title finds the installed media apps that hold no
  session, merges them into the list in alphabetical order, and a
  swipe discards them.

  @requires-music-assistant
  Scenario: An empty screen on first open
    Given a fresh install
    And a Music Assistant is present on the network
    When the app opens
    Then the Connect control is disabled
    And the Source card says that no app holds a media session
    And the Destination card presents connection options:
      | Sign into Music Assistant |
      | Pair with Music Assistant |

  @requires-music-assistant
  Scenario: Connect enables when both cards carry a pick
    Given Tidal holds a playing session
    And "Sign into Music Assistant" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    When "Tidal" is picked on the Source card
    And the first player is picked on the Destination card
    Then the Connect control is enabled

  @requires-music-assistant
  Scenario: Connect alone is not enough
    Given Tidal holds a playing session
    When "Tidal" is picked on the Source card
    Then the Connect control is disabled
    And the Destination card asks for a player pick

  Scenario: The Source list follows the sessions
    Given the app is open
    When Spotify starts a media session
    Then the Source card lists Spotify
    When Spotify drops its media session
    Then the Source card no longer lists Spotify

  Scenario: Row transport controls drive the picked app
    Given Tidal holds a playing session
    And "Tidal" is picked on the Source card
    When Pause is pressed on the Tidal row
    And Play is pressed on the Tidal row
    Then Tidal plays again

  Scenario: The layout adapts to the width
    Given Tidal holds a playing session
    And "Tidal" is picked on the Source card
    When the phone is turned to landscape
    Then the Source card and the Destination card sit side by side
    And "Tidal" stays picked on the Source card

  @requires-music-assistant
  @streaming
  Scenario: Connect starts the stream
    Given Tidal holds a playing session
    And "Sign into Music Assistant" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    And "Tidal" is picked on the Source card
    And the first player is picked on the Destination card
    When "Connect" is pressed
    Then the media projection consent dialog is presented
    When "Start now" is chosen on the consent dialog
    Then the phone streams to the first player
    And the control reads "Disconnect"
    And the notification offers Stop

  @requires-music-assistant
  Scenario: Disconnect stops the stream and keeps the picks
    Given the stream sends Tidal's audio to the first player
    When "Disconnect" is pressed
    Then the stream stops
    And the capture stops
    And the destination stops playing the phone
    And "Tidal" stays picked on the Source card
    And the first player stays picked on the Destination card
    And the control reads "Connect"

  @requires-music-assistant
  Scenario: The source changes while streaming
    Given the stream sends Tidal's audio to the first player
    When Spotify starts a media session
    And "Spotify" is picked on the Source card
    Then the stream plays Spotify's audio

  @requires-music-assistant
  @followpick
  Scenario: A paused pick holds while nothing else plays
    Given the stream sends Tidal's audio to the first player
    When Pause is pressed on the Tidal row
    Then "Tidal" stays picked on the Source card
    And the stream keeps sending silence to the first player
    And the control still reads "Disconnect"

  @requires-music-assistant
  Scenario: The destination changes while streaming
    Given the stream sends Tidal's audio to the first player
    When the second player is picked on the Destination card
    Then the stream plays to the second player
    When "Disconnect" is pressed
    Then the stream stops
    And the destination stops playing the phone

  @requires-music-assistant
  Scenario: The picked source vanishes mid-stream
    Given the stream sends Tidal's audio to the first player
    When Tidal drops its media session
    Then the Source card no longer lists Tidal
    And a status line under the bar says the source vanished
    And the stream keeps sending silence to the first player
    And the control still reads "Disconnect"

  @discovery
  Scenario: The plus finds the installed media apps
    Given the app is open
    And the finder offers "Mellow" and "Radio" as installed media apps
    When the plus is chosen
    Then the Source card lists "Mellow" as not running
    And the Source card lists "Radio" as not running

  @discovery
  Scenario: Discovered rows read among the sessions, alphabetically
    Given Tidal holds a playing session
    And the finder offers "Radio" and "Mellow" as installed media apps
    When the plus is chosen
    Then the rows read "Mellow, Radio, Tidal"

  @discovery
  Scenario: The finder leaves out the apps that hold a session
    Given Tidal holds a playing session
    And the finder offers "Tidal" as an installed media app
    When the plus is chosen
    Then the rows read "Tidal"

  @discovery
  Scenario: A swipe discards a discovered app
    Given the finder offers "Radio" as an installed media app
    When the plus is chosen
    And the Radio row is swiped aside
    Then the Source card no longer lists Radio
    And the finder does not offer "Radio" again

  @requires-music-assistant
  @discovery
  Scenario: Connect enables when the pick is a discovered app
    Given "Sign into Music Assistant" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    And the finder offers "Radio" as an installed media app
    When the plus is chosen
    And "Radio" is picked on the Source card
    And the first player is picked on the Destination card
    Then the Connect control is enabled
