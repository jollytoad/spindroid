@two-card
Feature: The two-card sender screen

  The primary screen of SpinDroid is two always-present cards.
  The Source card lists the apps that hold a media session, and the
  Destination card lists the players the network offers. The second
  of the two picks starts the stream on its own, and the stop on the
  picked destination row ends it. A swipe drops a destination row
  until the Show all players control reads the list again, and the
  row that carries the pick refuses the swipe.

  @requires-music-assistant
  Scenario: A first open shows the two cards
    Given a fresh install
    And a Music Assistant is present on the network
    And the phone holds no media session
    When the app opens
    Then the Source card offers to show all media apps
    And the Destination card offers the Music Assistant row

  @requires-music-assistant
  Scenario: The second pick starts the stream
    Given Tidal holds a playing session
    And "Sign in" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    And "Tidal" is picked on the Source card
    When the first player is picked on the Destination card
    Then the media projection consent dialog is presented
    When "Start now" is chosen on the consent dialog
    Then the phone streams to the first player
    And the notification offers Stop

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
  @followpick
  Scenario: The source side of the stream
    Given the stream sends Tidal's audio to the first player
    And the first player's row offers the stop
    When Pause is pressed on the Tidal row
    Then "Tidal" stays picked on the Source card
    And the stream keeps sending silence to the first player
    And the first player's row offers the stop
    When Mellow starts a media session
    And "Mellow" is picked on the Source card
    Then the stream plays Mellow's audio
    When Mellow drops its media session
    Then the Source card no longer lists Mellow
    And a status line under the bar says the source vanished
    And the stream keeps sending silence to the first player
    And the first player's row offers the stop

  @requires-music-assistant
  Scenario: The destination side of the stream
    Given the stream sends Tidal's audio to the first player
    When the second player is picked on the Destination card
    Then the stream plays to the second player
    When the second player row is swiped aside
    Then the Destination card still lists the second player
    And the second player stays picked on the Destination card
    And the second player's row carries the live output bars
    And the second player's row offers the stop
    When the stop on the second player's row is pressed
    Then the stream stops
    And the capture stops
    And the destination stops playing the phone
    And no destination row carries the live output bars
    And no destination row offers the stop
    And "Tidal" stays picked on the Source card
    And the second player stays picked on the Destination card

  @requires-music-assistant
  Scenario: A swipe discards a destination player
    Given "Sign in" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    When the second player row is swiped aside
    Then the Destination card no longer lists the second player
    When Show all players is chosen
    Then the Destination card lists the second player again
