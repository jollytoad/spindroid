@add-sources
Feature: Add sources to the card

  The Source card lists the apps that hold a media session. The plus
  beside its title finds the installed media apps that hold none, and
  merges them into the list as dimmed rows, in the alphabetical
  order. A swipe discards one, and the app icon on a row is the one
  control that opens the app: a tap anywhere else on the row picks
  it, and no pick ever pulls an app to the front.

  @discovery
  Scenario: The plus finds the installed media apps
    Given the app is open
    And the finder offers "Mellow" and "Radio" as installed media apps
    When the plus is chosen
    Then the Source card lists Mellow
    And the Source card lists Radio

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
  Scenario: A discovered row switches to a session row on arrival
    Given the finder offers "Mellow" as an installed media app
    When the plus is chosen
    And "Mellow" is picked on the Source card
    And Mellow starts a media session
    Then the rows read "Mellow"

  @discovery
  Scenario: A swipe discards a discovered app
    Given the finder offers "Radio" and "Tidal" as installed media apps
    When the plus is chosen
    And the Radio row is swiped aside
    Then the Source card no longer lists Radio
    And the Source card lists Tidal
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
