@phone-volume
Feature: The phone and its volume

  The phone sends what it plays, and the destination plays it back, so
  the phone's own speaker doubles the sound. While the capture runs,
  the phone therefore goes quiet, and it comes back when the stream
  ends. The capture reads the sound before the phone's volume applies
  to it, so the quiet phone still feeds the stream. The volume keys
  and the sliders drive the players instead, while the phone keeps its
  own volume to itself.

  @requires-music-assistant
  Scenario: A quiet phone still feeds the stream
    Given the phone's media volume reads 12
    And a tone plays on the phone
    And the stream sends Tidal's audio to the first player
    And the captured sound rises above silence
    When the phone's media volume reads 0
    Then the captured sound keeps its level

  @requires-music-assistant
  Scenario: The phone goes quiet for the stream and comes back
    Given the phone's media volume reads 12
    And the stream sends Tidal's audio to the first player
    Then the phone's media volume still reads 0
    When the stop on the first player's row is pressed
    Then the stream stops
    And the phone's media volume still reads 12

  @requires-music-assistant
  Scenario: A phone the user muted stays muted
    Given the phone's media volume reads 0
    And the stream sends Tidal's audio to the first player
    Then the phone's media volume still reads 0
    When the stop on the first player's row is pressed
    Then the phone's media volume still reads 0

  @requires-music-assistant
  Scenario: The phone keys drive the picked player
    Given the stream sends Tidal's audio to the first player
    And the first player's volume is set to 40
    When the volume key is pressed 2 times
    Then the first player's volume reads 50
    And the phone's media volume still reads 0

  @requires-music-assistant
  Scenario: The slider of a player sets its volume
    Given "Sign in" is chosen
    And the sign in form is filled with the Music Assistant account
    And "Sign in" is pressed
    And the app is signed into Music Assistant
    When the slider of the first player is dragged to its end
    Then the first player's volume reads high
