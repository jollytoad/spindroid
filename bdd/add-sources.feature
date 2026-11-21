@add-sources
Feature: Add sources to the card

  The Source card lists the apps that hold a media session. The finder
  beside its title finds the installed media apps that hold none, and
  merges them into the list as dimmed rows, in the alphabetical
  order. Every run of the finder forgets the rows a swipe dropped. A
  tap on a discovered row opens the app and picks it, so the pick
  waits for the app to play, and no pick ever pulls an app to the
  front on its own.

  @discovery
  Scenario: The finder lists the installed media apps
    Given the app is open
    And the finder offers "Mellow" and "Radio" as installed media apps
    When the finder is chosen
    Then the Source card lists Mellow
    And the Source card lists Radio

  @discovery
  Scenario: Discovered rows read among the sessions, alphabetically
    Given Tidal holds a playing session
    And the finder offers "Radio" and "Mellow" as installed media apps
    When the finder is chosen
    Then the rows read "Mellow, Radio, Tidal"

  @discovery
  Scenario: The finder leaves out the apps that hold a session
    Given Tidal holds a playing session
    And the finder offers "Tidal" as an installed media app
    When the finder is chosen
    Then the rows read "Tidal"

  @discovery
  Scenario: A discovered row switches to a session row on arrival
    Given the finder offers "Mellow" as an installed media app
    When the finder is chosen
    And "Mellow" is picked on the Source card
    And Mellow starts a media session
    Then the rows read "Mellow"

  @discovery
  Scenario: A session shows its row before it names its track
    Given the app is open
    And no media app holds a session
    And the finder offers "Mellow" as an installed media app
    When the finder is chosen
    Then the Source card lists Mellow
    And Mellow starts a quiet media session
    Then the rows read "Mellow, SpinDroid"
    And the SpinDroid row offers Pause
    When Mellow names its track
    Then the rows read "Mellow"

  @discovery
  Scenario: A swipe discards a discovered app
    Given the finder offers "Radio" and "Tidal" as installed media apps
    When the finder is chosen
    And the Radio row is swiped aside
    Then the Source card no longer lists Radio
    And the Source card lists Tidal
    And the finder offers "Radio" again

  @discovery
  Scenario: A row tap opens the app
    Given the phone holds no other playing app
    And the finder offers Spotify from this phone
    When the finder is chosen
    And "Spotify" is picked on the Source card
    Then Spotify opens in the foreground

  @discovery
  Scenario: The row icon opens the app
    Given the phone holds no other playing app
    And the finder offers Spotify from this phone
    When the finder is chosen
    And the Spotify icon is chosen
    Then Spotify opens in the foreground

  @requires-music-assistant
  @discovery
  Scenario: Connect enables when the pick is a discovered app
    Given "Sign in" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    And the finder offers "Radio" as an installed media app
    When the finder is chosen
    And "Radio" is picked on the Source card
    And the first player is picked on the Destination card
    Then the Connect control is enabled
