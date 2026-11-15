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

  Scenario: Signing into Music Assistant
    Given a fresh install
    And a Music Assistant is present on the network
    When the app opens
    And "Sign into Music Assistant" is chosen
    Then a sign in page is shown
    And the page has a sign in form
    And the form has the fields:
      | Server    |
      | User name |
      | Password  |
    And the Server field is pre-populated with the Music Assistant server URL
    And these buttons are presented:
      | Sign in |
      | Cancel  |
