package com.sendspin.protocol.pairing.cpace

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The CPace tests against the vectors of draft-irtf-cfrg-cpace.
 *
 * The draft gives no vectors for the confirmation tags, because it leaves
 * the MAC open. SendSpin pins the MAC to HMAC-SHA-512, so the tags come
 * from the reference implementation that the reference server depends on.
 */
class CPaceResponderTest {
    @Test
    fun `the generator matches the draft vector`() {
        assertArrayEquals(
            hex("D04BF6D41F6A289632A2E929FA29BEBD51092512A7829FDDE7D314B62F05A73F"),
            CPaceX25519.calculateGenerator(hex(PRS), hex(CI), hex(SID)),
        )
    }

    @Test
    fun `the public shares match the draft vector`() {
        val generator = CPaceX25519.calculateGenerator(hex(PRS), hex(CI), hex(SID))
        assertArrayEquals(hex(YA), CPaceX25519.scalarMultVfy(hex("21B4F4BD9E64ED355C3EB676A28EBEDAF6D8F17BDC365995B319097153044080"), generator))
        assertArrayEquals(hex(YB), CPaceX25519.scalarMultVfy(hex("848B0779FF415F0AF4EA14DF9DD1D3C29AC41D836C7808896C4EBA19C51AC40A"), generator))
    }

    @Test
    fun `the shared secret matches the draft vector`() {
        assertArrayEquals(
            hex("5B067EFFBDC0B2A0E1D907B21EBB25CFEDB96A852179A847C37E43EE71322C6B"),
            CPaceX25519.scalarMultVfy(
                hex("21B4F4BD9E64ED355C3EB676A28EBEDAF6D8F17BDC365995B319097153044080"),
                hex(YB),
            ),
        )
    }

    @Test
    fun `the session key matches the draft vector`() {
        // The draft vector uses its own associated data, "ADA" and "ADB".
        // The SendSpin values are "server" and "client", so the primitive is
        // checked here and the whole responder is checked against the
        // reference server below.
        assertArrayEquals(
            hex("6E19B875F7A561D6B3CA3DBB9EF42AC55DE3E717881018204B8922B4D5E53BB2AA82C300BEA7B65D2B671DA71922DDF6472301B79BC270ADFA8BF413285F2263"),
            CPaceX25519.deriveIsk(
                sid = hex(SID),
                shared = hex("5B067EFFBDC0B2A0E1D907B21EBB25CFEDB96A852179A847C37E43EE71322C6B"),
                ya = hex(YA),
                ada = hex("414461"),
                yb = hex(YB),
                adb = hex("414462"),
            ),
        )
    }

    @Test
    fun `a low-order point is refused`() {
        val responder = CPaceResponder(hex(PRS), hex(SID))
        val points = mapOf(
            "Invalid Y0" to "0000000000000000000000000000000000000000000000000000000000000000",
            "Invalid Y1" to "0100000000000000000000000000000000000000000000000000000000000000",
            "Invalid Y2" to "ECFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF7F",
            "Invalid Y3" to "E0EB7A7C3B41B8AE1656E3FAF19FC46ADA098DEB9C32B1FD866205165F49B800",
            "Invalid Y4" to "5F9C95BCA3508C24B1D0B1559C83EF5B04445CC4581C8E86D8224EDDD09F1157",
            "Invalid Y5" to "EDFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF7F",
            "Invalid Y7" to "EEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF7F",
        )
        for ((name, point) in points) {
            assertNull(
                CPaceX25519.scalarMultVfy(hex(VERIFY_SCALAR), hex(point)),
                "$name must be refused",
            )
        }
    }

    @Test
    fun `a point that is low-order only without the top bit cleared gives the draft value`() {
        val expected = mapOf(
            "Invalid Y6" to ("DAFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" to "d8e2c776bbacd510d09fd9278b7edcd25fc5ae9adfba3b6e040e8d3b71b21806"),
            "Invalid Y8" to ("DBFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" to "c85c655ebe8be44ba9c0ffde69f2fe10194458d137f09bbff725ce58803cdb38"),
            "Invalid Y9" to ("D9FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF" to "db64dafa9b8fdd136914e61461935fe92aa372cb056314e1231bc4ec12417456"),
            "Invalid Y10" to ("CDEB7A7C3B41B8AE1656E3FAF19FC46ADA098DEB9C32B1FD866205165F49B880" to "e062dcd5376d58297be2618c7498f55baa07d7e03184e8aada20bca28888bf7a"),
            "Invalid Y11" to ("4C9C95BCA3508C24B1D0B1559C83EF5B04445CC4581C8E86D8224EDDD09F11D7" to "993c6ad11c4c29da9a56f7691fd0ff8d732e49de6250b6c2e80003ff4629a175"),
        )
        for ((name, pair) in expected) {
            assertArrayEquals(
                hex(pair.second),
                CPaceX25519.scalarMultVfy(hex(VERIFY_SCALAR), hex(pair.first)),
                name,
            )
        }
    }

    @Test
    fun `the digits run matches the reference server`() {
        val run = run(
            prs = "313233343536",
            sid = "73656e647370696e2d706169722d70616b652d7631000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000100000001",
            ya = "290f6bd1559626855a6b05bb67f1f364356ad16184f0ab898339a45c1fee6601",
            yb = "23cbc858e3e96f476800600beeefa665a23cae3a58f44be371589385efe71156",
            isk = "3ea260815b47e68b6cc07a22038452efc470e85425bc8485344a2987da473ca95cee9bbd47933370bb4c4626581c47c5f24e55a12aa1f6e0375560014210bf70",
            serverKc = "97cd8c996a88c39bcd03f6abf26e529c2b848ad2263c791ba13e2544217bd4079912b2ecd4e78139e92fb0ead22d137126bf30e237325cbde8b6a839fb1ea6a1",
            clientKc = "38fb10d9677e2e27b3113ea371469657a13442654ac7f318423437fd48a5213f385002bedc22e4197511087058136038f01cbac0bcee8ae82e71fd89ba7a0a5b",
        )
        assertEquals(5, run)
    }

    @Test
    fun `the second round matches the reference server`() {
        val run = run(
            prs = "313233343536",
            sid = "73656e647370696e2d706169722d70616b652d7631000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000100000002",
            ya = "35d619e1d0e6bc3f19df608df23cbb0b55c0603cc94afc8310fcee691765a505",
            yb = "5a0cff21905b9ae5a1cabc9790a8e43e7ad24563d715c106432c67da78fa696c",
            isk = "3f32c3225e9dd5db3f9b922d5da502f5d0d3982f5b1d13040ed9d7420a67e84086d063325bfbf0ec11971305dad884d73197606e258399abdf27bde117920e67",
            serverKc = "6200a2f94432b5934c1af4a3a196fc0b179dcfcc872490eeade3e2c0db8c7c977ddaf801275dd2972b8ec612dd020a89503f918acd188f551bd9521de7b0e063",
            clientKc = "964d56fe6a0d1642a9a824a46e4078480af975236ee51b97005b9634f25b5447b09198ca1d01dd51ddfbdd5b6f92316f36d1a09d70e8236ecdee63d258851f51",
        )
        assertEquals(5, run)
    }

    @Test
    fun `the binary code run matches the reference server`() {
        val run = run(
            prs = "e0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7",
            sid = "73656e647370696e2d706169722d70616b652d7631000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f0000000100000001",
            ya = "2d798a7e991299e073390aa8cc301a9dd9c053a33630f57fb9fd0a63d75e6f1c",
            yb = "7237d3cc63109cc8cbcde36989ab9bbefc6e9032a3c91c995eed51f6334f665f",
            isk = "dc6044c23f4190145ba5a7db8934e9e22b8f600c89743b2431902cee28201055d58a45e357be51351600d98fd51477de8bce5c799cb3d6ab3d1cec40fde4db75",
            serverKc = "fd74be689e5970ae0fc3b175290c6f3e9a5f545ef6c50387c3dbcc79c8910ebdc206d74dc29dd97abef091feaee7b67048e24ac69e4ef1b565cbce450a321856",
            clientKc = "8353acad7d0245e250cea9949d9052d2932cd203849123aad374565cb8416b2776e70ee7ea491a96434c6ac8f3370a2f042c159004d8896c5d7b602ae4343042",
        )
        assertEquals(5, run)
    }

    @Test
    fun `a wrong tag is refused`() {
        val responder = CPaceResponder("123456".toByteArray(), "a session id".toByteArray())
        assertTrue(responder.derive(ByteArray(32) { 9 }))
        val tag = responder.tag()
        val wrong = tag.copyOf()
        wrong[0] = (wrong[0].toInt() xor 1).toByte()
        assertFalse(responder.verify(wrong))
        assertFalse(responder.verify(ByteArray(10)))
    }

    @Test
    fun `a wrong password gives a different session key`() {
        val sid = "a session id".toByteArray()
        val right = CPaceResponder("123456".toByteArray(), sid)
        val wrong = CPaceResponder("123457".toByteArray(), sid)
        val peer = ByteArray(32) { 3 }
        assertTrue(right.derive(peer))
        assertTrue(wrong.derive(peer))
        assertFalse(right.isk.contentEquals(wrong.isk))
    }

    /**
     * Runs one CPace exchange and checks it against the reference values.
     *
     * @return the number of checks that passed
     */
    private fun run(
        prs: String,
        sid: String,
        ya: String,
        yb: String,
        isk: String,
        serverKc: String,
        clientKc: String,
    ): Int {
        val responder = CPaceResponder(hex(prs), hex(sid), scalar = hex(SCALAR))
        var checks = 0
        assertArrayEquals(hex(yb), responder.publicShare)
        checks++
        assertTrue(responder.derive(hex(ya)))
        checks++
        assertArrayEquals(hex(isk), responder.isk)
        checks++
        assertArrayEquals(hex(clientKc), responder.tag())
        checks++
        assertTrue(responder.verify(hex(serverKc)))
        checks++
        return checks
    }

    private fun hex(value: String): ByteArray {
        val clean = value.replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            out[index] = clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private companion object {
        const val PRS = "50617373776F7264"
        const val CI = "0B415F696E69746961746F720B425F726573706F6E646572"
        const val SID = "7E4B4791D6A8EF019B936C79FB7F2C57"
        const val YA = "1D13C89278CDADD826F6D8D7F887701430F8380DDC17611CDD6DC989CE0C9F32"
        const val YB = "248CCCF6D5CDC3646F0AD593F9E6CEF4E69D4945F8372E623512ECEA32185623"
        const val VERIFY_SCALAR = "af46e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449aff"

        /** The scalar that the reference run used, so the public share is fixed. */
        const val SCALAR = "025984ca800ed7505e9f20a4b92314c3721e16112fe1447bd807e2fcf9813398"
    }
}
