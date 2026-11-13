Feature: SendSpin Source Role

  The app can act a SendSpin client with the source role.
  It advertises itself on the network for Music Assistant (MA) or other
  SendSpin server to discover. It can then pair with the server
  and start sending audio captured from the Android device.

  Scenario: Onboarding in the presence of Music Assistant
    Given a fresh install
    And a Music Assistant is present on the network
    When the app opens
    Then the Music Assistant name is shown
    And connection options are presented:
      | Sign into Music Assistant |
      | Pair with Music Assistant |
