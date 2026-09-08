package com.alive.player.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the allow-list semantics of the destructive-command sender check: reboot must
 * execute ONLY for a direct-to-token send, whose `from` is the app's own numeric
 * sender id. The previous guard deny-listed `startsWith("/topics/")` and was
 * bypassable by a topic-*condition* send ("'x' in topics" — a fleet broadcast whose
 * `from` is not slash-prefixed); these cases pin that every broadcast shape, known or
 * future, fails the check rather than depending on how FCM happens to format `from`.
 * As with UpdateInstallerGuardTest, the wiring (onMessageReceived consulting this
 * with FirebaseApp's gcmSenderId) is by inspection — the service needs a Firebase
 * Context this project's JUnit-only setup cannot provide.
 */
class FcmSenderGuardTest {

    private val senderId = "103978029517" // shape of a real project number, any digits do

    @Test
    fun `direct-to-token send is allowed`() {
        assertTrue(isFromOwnSender(senderId, senderId))
    }

    @Test
    fun `topic send is rejected`() {
        assertFalse(isFromOwnSender("/topics/all-devices", senderId))
    }

    @Test
    fun `topic-condition send is rejected — the bypass this guard exists to close`() {
        assertFalse(isFromOwnSender("'all-devices' in topics", senderId))
        assertFalse(isFromOwnSender("'a' in topics && 'b' in topics", senderId))
    }

    @Test
    fun `a different sender id is rejected`() {
        assertFalse(isFromOwnSender("999999999999", senderId))
    }

    @Test
    fun `null from fails closed`() {
        assertFalse(isFromOwnSender(null, senderId))
    }

    @Test
    fun `missing own sender id fails closed`() {
        assertFalse(isFromOwnSender(senderId, null))
    }
}
