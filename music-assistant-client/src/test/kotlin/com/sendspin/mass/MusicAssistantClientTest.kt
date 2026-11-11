package com.sendspin.mass

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

    /** The answer the server gives. */
    private var answer: String = ""

    /** The status the server answers with. */
    private var status: Int = 200

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
        server.createContext("/api") { exchange ->
            receivedPath = exchange.requestURI.path
            receivedBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val bytes = answer.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `the sign in succeeds`() {
        answer = """
            {"success":true,"access_token":"a-token","user":{"username":"sam","display_name":"Sam","role":"admin"}}
        """.trimIndent()

        val result = MusicAssistantClient(baseUrl).login("sam", "hunter2", "SendSpin")

        assertTrue(result is LoginResult.Success, "the sign in should succeed, it gave $result")
        val success = result as LoginResult.Success
        assertEquals("a-token", success.token)
        assertEquals("sam", success.username)
        assertEquals("Sam", success.displayName)
    }

    @Test
    fun `the command carries the name and the password`() {
        answer = """{"success":true,"access_token":"a-token","user":{"username":"sam"}}"""

        MusicAssistantClient(baseUrl).login("sam", "hunter2", "SendSpin")

        assertEquals("/api", receivedPath)
        assertTrue(receivedBody.contains("\"command\":\"auth/login\""), "the body was $receivedBody")
        assertTrue(receivedBody.contains("\"username\":\"sam\""), "the body was $receivedBody")
        assertTrue(receivedBody.contains("\"password\":\"hunter2\""), "the body was $receivedBody")
        assertTrue(receivedBody.contains("\"device_name\":\"SendSpin\""), "the body was $receivedBody")
    }

    @Test
    fun `the wrong password gives the reason of the server`() {
        answer = """{"success":false,"error":"Invalid username or password"}"""

        val result = MusicAssistantClient(baseUrl).login("sam", "wrong", "SendSpin")

        assertTrue(result is LoginResult.Failure, "the sign in should fail, it gave $result")
        assertEquals("Invalid username or password", (result as LoginResult.Failure).reason)
    }

    @Test
    fun `a server that is not there gives a failure`() {
        val result = MusicAssistantClient("http://127.0.0.1:1").login("sam", "hunter2", "SendSpin")

        assertTrue(result is LoginResult.Failure, "the sign in should fail, it gave $result")
    }

    @Test
    fun `an answer that is not JSON gives a failure`() {
        answer = "<html>not the API</html>"

        val result = MusicAssistantClient(baseUrl).login("sam", "hunter2", "SendSpin")

        assertTrue(result is LoginResult.Failure, "the sign in should fail, it gave $result")
    }

    @Test
    fun `an error status with a body gives the reason of the server`() {
        status = 401
        answer = """{"success":false,"error":"Invalid username or password"}"""

        val result = MusicAssistantClient(baseUrl).login("sam", "wrong", "SendSpin")

        assertTrue(result is LoginResult.Failure, "the sign in should fail, it gave $result")
        assertEquals("Invalid username or password", (result as LoginResult.Failure).reason)
    }
}
