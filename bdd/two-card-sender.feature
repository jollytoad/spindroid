@two-card
Feature: The two-card sender screen

  The primary screen of SpinDroid is two always-present cards.
  The Source card lists the apps that hold a media session, and the
  Destination card lists the players the network offers. Connect
  streams the picked source to the picked destination.

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
    Then the stream sends Tidal's audio to the first player
    And the control reads "Disconnect"
    And the notification offers Stop

  @requires-music-assistant
  Scenario: Disconnect stops the stream and keeps the picks
    Given the stream sends Tidal's audio to the first player
    When "Disconnect" is pressed
    Then the stream stops
    And the capture stops
    And "Tidal" stays picked on the Source card
    And the first player stays picked on the Destination card
    And the control reads "Connect"

  @requires-music-assistant
  Scenario: The Source card is read-only while streaming
    Given the stream sends Tidal's audio to the first player
    When the Source card is viewed
    Then the Source radios are disabled
    And the card says to disconnect before picking another source

  @requires-music-assistant
  Scenario: The Destination card is read-only while streaming
    Given the stream sends Tidal's audio to the first player
    When the Destination card is viewed
    Then the Destination radios are disabled
    And the card says to disconnect before picking another player

  @requires-music-assistant
  Scenario: The picked source vanishes mid-stream
    Given the stream sends Tidal's audio to the first player
    When Tidal drops its media session
    Then the Source card no longer lists Tidal
    And a status line under the bar says the source vanished
    And the stream keeps sending silence to the first player
    And the control still reads "Disconnect"
