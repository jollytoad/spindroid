package app.gubbins.mass

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The client against a real HTTP server.
 *
 * The server is the JDK's own, so the test runs the client over a real
 * socket. That catches the mistakes a stub cannot: the wrong path, a body
 * that the server cannot parse, a header that is missing.
 */
class MusicAssistantClientTest {
    private lateinit var server: HttpServer
    private var baseUrl: String = ""

    /** The body the server received. */
    private var receivedBody: String = ""

    /** The path the server was asked for. */
    private var receivedPath: String = ""

    /** The header the server was given. */
    private var receivedAuthorization: String? = null

    /** The answer the server gives, per command. */
    private val answers = mutableMapOf<String, Answer>()

    /** The answer the server gives when the command is not in the map. */
    private var fallback: Answer = Answer(200, "null")

    /** One answer of the server. */
    private data class Answer(val status: Int, val body: String)

    private val moshi = Moshi.Builder().build()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/api") { exchange ->
            receivedPath = exchange.requestURI.path
            receivedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            receivedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val answer = answers[commandOf(receivedBody)] ?: fallback
            val bytes = answer.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(answer.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    /**
     * The name of the command in a body.
     *
     * The body is read by the server, so the test reads it the same way.
     */
    private fun commandOf(body: String): String? {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val map = moshi.adapter<Map<String, Any>>(type).fromJson(body) ?: return null
        return map["command"] as? String
    }

    @Test
    fun `the sign in succeeds`() {
        answers[MusicAssistantClient.COMMAND_LOGIN] = Answer(
            200,
            """
            {"success":true,"access_token":"a-token","user":{"username":"sam","display_name":"Sam","role":"admin"}}
            """.trimIndent(),
        )

        val result = MusicAssistantClient(baseUrl).login("sam", "hunter2", "SpinDroid")

        assertTrue(result is LoginResult.Success, "the sign in should succeed, it gave $result")
        val success = result as LoginResult.Success
        assertEquals("a-token", success.token)
        assertEquals("sam", success.username)
        assertEquals("Sam", success.displayName)
    }

    @Test
    fun `the command carries the name and the password`() {
        answers[MusicAssistantClient.COMMAND_LOGIN] = Answer(
            200,
            """{"success":true,"access_token":"a-token","user":{"username":"sam"}}""",
        )

        MusicAssistantClient(baseUrl).login("sam", "hunter2", "SpinDroid")

        assertEquals("/api", receivedPath)
        assertTrue(receivedBody.contains("\"command\":\"auth/login\""), "the body was $receivedBody")
        assertTrue(receivedBody.contains("\"username\":\"sam\""), "the body was $receivedBody")
        assertTrue(receivedBody.contains("\"password\":\"hunter2\""), "the body was $receivedBody")
        assertTrue(receivedBody.contains("\"device_name\":\"SpinDroid\""), "the body was $receivedBody")
    }

    @Test
    fun `the wrong password gives the reason of the server`() {
        answers[MusicAssistantClient.COMMAND_LOGIN] =
            Answer(200, """{"success":false,"error":"Invalid username or password"}""")

        val result = MusicAssistantClient(baseUrl).login("sam", "wrong", "SpinDroid")

        assertTrue(result is LoginResult.Failure, "the sign in should fail, it gave $result")
        assertEquals("Invalid username or password", (result as LoginResult.Failure).reason)
    }

    @Test
    fun `a server that is not there gives a failure`() {
        val result = MusicAssistantClient("http://127.0.0.1:1").login("sam", "hunter2", "SpinDroid")

        assertTrue(result is LoginResult.Failure, "the sign in should fail, it gave $result")
    }

    @Test
    fun `an answer that is not JSON gives a failure`() {
        answers[MusicAssistantClient.COMMAND_LOGIN] = Answer(200, "<html>not the API</html>")

        val result = MusicAssistantClient(baseUrl).login("sam", "hunter2", "SpinDroid")

        assertTrue(result is LoginResult.Failure, "the sign in should fail, it gave $result")
    }

    @Test
    fun `an error status with a body gives the reason of the server`() {
        answers[MusicAssistantClient.COMMAND_LOGIN] =
            Answer(401, """{"success":false,"error":"Invalid username or password"}""")

        val result = MusicAssistantClient(baseUrl).login("sam", "wrong", "SpinDroid")

        assertTrue(result is LoginResult.Failure, "the sign in should fail, it gave $result")
        val reason = (result as LoginResult.Failure).reason
        assertTrue(
            reason.endsWith("Invalid username or password"),
            "the reason should carry the words of the server, it was $reason",
        )
    }

    @Test
    fun `the list of players is read`() {
        answers[MusicAssistantClient.COMMAND_PLAYERS_ALL] = Answer(
            200,
            """
            [
              {"player_id":"garage","name":"Garage","display_name":"Garage","type":"player",
               "provider":"chromecast","available":true,"enabled":true,"state":"idle",
               "active_source":"garage","group_members":[],"synced_to":null},
              {"player_id":"radio","name":"radio","display_name":"radio","type":"player",
               "provider":"squeezelite","available":false,"enabled":true,"state":"playing",
               "active_source":"sendspin_source://audio_source/abc","group_members":["kitchen"],
               "synced_to":null}
            ]
            """.trimIndent(),
        )

        val result = MusicAssistantClient(baseUrl).listPlayers("a-token")

        assertTrue(result is PlayersResult.Success, "the list should arrive, it gave $result")
        val players = (result as PlayersResult.Success).players
        assertEquals(2, players.size)
        assertEquals("garage", players[0].playerId)
        assertEquals("Garage", players[0].label)
        assertEquals("chromecast", players[0].provider)
        assertTrue(players[0].playsAudio)
        assertTrue(!players[0].isGroup)
        assertEquals("sendspin_source://audio_source/abc", players[1].activeSource)
        assertEquals(listOf("kitchen"), players[1].groupMembers)
        assertEquals("playing", players[1].state)
        assertEquals(false, players[1].available)
    }

    @Test
    fun `the list of players carries the volume`() {
        answers[MusicAssistantClient.COMMAND_PLAYERS_ALL] = Answer(
            200,
            """
            [
              {"player_id":"garage","name":"Garage","volume_level":56,"available":true},
              {"player_id":"radio","name":"radio","volume_level":null,"available":false}
            ]
            """.trimIndent(),
        )

        val result = MusicAssistantClient(baseUrl).listPlayers("a-token")

        assertTrue(result is PlayersResult.Success, "the list should arrive, it gave $result")
        val players = (result as PlayersResult.Success).players
        assertEquals(56, players[0].volumeLevel)
        assertEquals(null, players[1].volumeLevel)
    }

    @Test
    fun `the volume command carries the player and the level as a number`() {
        answers["players/cmd/volume_set"] = Answer(200, "null")

        val reason = MusicAssistantClient(baseUrl).setVolume("a-token", "garage", 55)

        assertEquals(null, reason)
        assertTrue(receivedBody.contains("\"command\":\"players/cmd/volume_set\""), "the body was $receivedBody")
        assertTrue(receivedBody.contains("\"player_id\":\"garage\""), "the body was $receivedBody")
        assertTrue(receivedBody.contains("\"volume_level\":55"), "the level must be a number, the body was $receivedBody")
    }

    @Test
    fun `the volume command reports a refusal`() {
        answers["players/cmd/volume_set"] = Answer(401, "Authentication required")

        val reason = MusicAssistantClient(baseUrl).setVolume("a-token", "garage", 55)

        assertEquals(
            "Music Assistant refused the command: Authentication required",
            reason,
        )
    }

    @Test
    fun `the list of players carries the token`() {
        answers[MusicAssistantClient.COMMAND_PLAYERS_ALL] = Answer(200, "[]")

        MusicAssistantClient(baseUrl).listPlayers("a-token")

        assertEquals("Bearer a-token", receivedAuthorization)
        assertTrue(receivedBody.contains("\"command\":\"players/all\""), "the body was $receivedBody")
    }

    @Test
    fun `a list of players that is not JSON gives a failure`() {
        answers[MusicAssistantClient.COMMAND_PLAYERS_ALL] = Answer(200, "<html>not the API</html>")

        val result = MusicAssistantClient(baseUrl).listPlayers("a-token")

        assertTrue(result is PlayersResult.Failure, "the list should fail, it gave $result")
    }

    @Test
    fun `a refused list of players gives the reason of the server`() {
        answers[MusicAssistantClient.COMMAND_PLAYERS_ALL] = Answer(401, "Authentication required")

        val result = MusicAssistantClient(baseUrl).listPlayers("a-token")

        assertTrue(result is PlayersResult.Failure, "the list should fail, it gave $result")
        assertEquals(
            "The server did not answer: Authentication required",
            (result as PlayersResult.Failure).reason,
        )
    }

    @Test
    fun `the choice of a player names the player and the source`() {
        answers[MusicAssistantClient.COMMAND_SELECT_SOURCE] = Answer(200, "null")

        val result = MusicAssistantClient(baseUrl)
            .selectSource("a-token", "garage", "sendspin_source://audio_source/abc")

        assertTrue(result is SelectSourceResult.Success, "the choice should work, it gave $result")
        assertEquals("Bearer a-token", receivedAuthorization)
        assertTrue(
            receivedBody.contains("\"command\":\"players/cmd/select_source\""),
            "the body was $receivedBody",
        )
        assertTrue(receivedBody.contains("\"player_id\":\"garage\""), "the body was $receivedBody")
        assertTrue(
            receivedBody.contains("\"source\":\"sendspin_source://audio_source/abc\""),
            "the body was $receivedBody",
        )
    }

    @Test
    fun `a refused choice of a player gives the reason of the server`() {
        answers[MusicAssistantClient.COMMAND_SELECT_SOURCE] = Answer(404, "Player garage is not available")

        val result = MusicAssistantClient(baseUrl)
            .selectSource("a-token", "garage", "sendspin_source://audio_source/abc")

        assertTrue(result is SelectSourceResult.Failure, "the choice should fail, it gave $result")
        assertEquals(
            "The server did not answer: Player garage is not available",
            (result as SelectSourceResult.Failure).reason,
        )
    }
}
