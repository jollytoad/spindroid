@two-card
Feature: The two-card sender screen

  The primary screen of SpinDroid is two always-present cards.
  The Source card lists the apps that hold a media session, and the
  Destination card lists the players the network offers. Connect
  streams the picked source to the picked destination. A swipe
  drops a destination row until the refresh control beside the title
  reads the list again, and the row that carries the pick refuses
  the swipe.

  @requires-music-assistant
  Scenario: An empty screen on first open
    Given a fresh install
    And a Music Assistant is present on the network
    And the phone holds no media session
    When the app opens
    Then the Connect control is disabled
    And the Source card says that no app holds a media session
    And the Destination card offers the Music Assistant row

  @requires-music-assistant
  Scenario: Connect follows the picks
    Given Tidal holds a playing session
    When "Tidal" is picked on the Source card
    Then the Connect control is disabled
    And the Destination card asks for a sign in
    When "Sign in" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    And the first player is picked on the Destination card
    Then the Connect control is enabled

  Scenario: The Source list follows the sessions
    Given the app is open
    When Mellow starts a media session
    Then the Source card lists Mellow
    When Mellow drops its media session
    Then the Source card no longer lists Mellow

  Scenario: Row transport controls drive the picked app
    Given Tidal holds a playing session
    And "Tidal" is picked on the Source card
    When Pause is pressed on the Tidal row
    And Play is pressed on the Tidal row
    Then Tidal plays again

  Scenario: The transport buttons follow the session state
    Given Tidal holds a playing session
    Then the Tidal row offers Pause
    And the Tidal row offers no Play
    And the Tidal row carries the playing bars
    When Tidal holds a paused session
    Then the Tidal row offers Play
    And the Tidal row offers no Pause
    And Quiet holds a session that answers no transport
    Then the Source card lists Quiet
    And the Quiet row offers no Play
    And the Quiet row offers no Pause

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
    And "Sign in" is chosen
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
  @followpick
  Scenario: The source side of the stream
    Given the stream sends Tidal's audio to the first player
    When Pause is pressed on the Tidal row
    Then "Tidal" stays picked on the Source card
    And the stream keeps sending silence to the first player
    And the control still reads "Disconnect"
    When Mellow starts a media session
    And "Mellow" is picked on the Source card
    Then the stream plays Mellow's audio
    When Mellow drops its media session
    Then the Source card no longer lists Mellow
    And a status line under the bar says the source vanished
    And the stream keeps sending silence to the first player
    And the control still reads "Disconnect"

  @requires-music-assistant
  Scenario: The destination side of the stream
    Given the stream sends Tidal's audio to the first player
    When the second player is picked on the Destination card
    Then the stream plays to the second player
    When the second player row is swiped aside
    Then the Destination card still lists the second player
    And the second player stays picked on the Destination card
    And the second player's row carries the live output bars
    When "Disconnect" is pressed
    Then the stream stops
    And the capture stops
    And the destination stops playing the phone
    And no destination row carries the live output bars
    And "Tidal" stays picked on the Source card
    And the second player stays picked on the Destination card
    And the control reads "Connect"

  @requires-music-assistant
  Scenario: A swipe discards a destination player
    Given "Sign in" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    When the second player row is swiped aside
    Then the Destination card no longer lists the second player
    When the destination refresh is chosen
    Then the Destination card lists the second player again
