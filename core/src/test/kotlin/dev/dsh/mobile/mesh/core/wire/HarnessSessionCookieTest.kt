package dev.dsh.mobile.mesh.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The expiry the app schedules a silent renewal from.
 *
 * The host mints an absolute lifetime on the exchange's `Set-Cookie`; losing it means the cookie
 * expires unnoticed and the next recovery answers 401. Hosts were not uniform about the attribute
 * (newer ones send `Max-Age`, older ones only `Expires`), so both parse while `Max-Age` wins.
 */
class HarnessSessionCookieTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `max age wins over the absolute date the same response carries`() {
        val header = "dsh-auth-abc=v1.eyJhIjoxfQ.sig; Max-Age=2592000; Path=/; " +
            "Expires=Wed, 21 Oct 2015 07:28:00 GMT; HttpOnly; SameSite=Strict"
        assertEquals(now + 2_592_000_000L, HarnessSession.sessionCookieExpiryMillis(header, now))
    }

    @Test
    fun `an expires-only host still yields an absolute expiry`() {
        val header = "dsh-auth-abc=v1.body.sig; Path=/; Expires=Wed, 21 Oct 2015 07:28:00 GMT; HttpOnly"
        assertEquals(1_445_412_480_000L, HarnessSession.sessionCookieExpiryMillis(header, now))
    }

    @Test
    fun `a cookie that declares no lifetime schedules no proactive renewal`() {
        val header = "dsh-auth-abc=v1.body.sig; Path=/; HttpOnly; SameSite=Strict"
        assertNull(HarnessSession.sessionCookieExpiryMillis(header, now))
    }
}
