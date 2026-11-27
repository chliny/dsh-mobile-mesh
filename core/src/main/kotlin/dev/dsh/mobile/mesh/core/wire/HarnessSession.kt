package dev.dsh.mobile.mesh.core.wire

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a launch-token exchange produced, or why it did not. */
sealed class SessionExchange {
    /**
     * The `Cookie` header value to send on every later request to this authority.
     *
     * [expiresAtMillis] is the absolute expiry the harness declared on the same response. Hosts
     * issue the cookie with an absolute lifetime and never roll it on later API traffic, so this
     * is the only moment the client learns when it must exchange the launch token again.
     */
    data class Granted(val cookie: String, val expiresAtMillis: Long? = null) : SessionExchange()

    /**
     * The harness answered but issued no session.
     *
     * Almost always a token from an earlier harness process: the token rotates on every start and
     * is never persisted, so a startup URL copied yesterday is simply not the current one.
     */
    data class Refused(val status: Int) : SessionExchange()

    /** The exchange never reached a harness. */
    data class Unreachable(val kind: TransportFailure, val message: String?) : SessionExchange()
}

/**
 * The browser-session exchange harness 0.1.2 requires before any `/api` call.
 *
 * From 0.1.2 the harness authenticates its complete API — every Remote call, the mux upgrade, and
 * the session-log download — against a signed cookie, and answers 401 without one. The only way
 * to obtain that cookie is to present the launch token the harness prints once per process to its
 * *index* route: `GET /?token=…`. The token is deliberately refused on `/api` paths and in an
 * `Authorization` header, so there is no alternative to this exchange and no way to fold it into
 * an ordinary call.
 *
 * The same token can be exchanged again for as long as its harness process runs, which is what
 * makes a silent renewal possible: a second exchange mints a new cookie with a fresh absolute
 * lifetime. Nothing here retries. A refusal means the token is not current, and asking again with
 * the same one produces the same answer.
 */
object HarnessSession {

    /** Cookie names the harness issues; the suffix is the normalized authority it is bound to. */
    private const val COOKIE_PREFIX = "dsh-auth-"

    /**
     * Exchange [token] at [baseUrl] for a browser session.
     *
     * Redirects are not followed: a successful exchange answers 3xx to the clean `/`, and the
     * cookie is on *that* response. Letting the HTTP layer chase it would discard the header this
     * whole call exists to read.
     */
    suspend fun exchange(
        baseUrl: String,
        token: String,
        client: OkHttpClient,
        timeoutMs: Long = 10_000,
        hostHeader: String? = null,
    ): SessionExchange = withContext(Dispatchers.IO) {
        val exchanger = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val url = baseUrl.trimEnd('/') + "/?token=" + java.net.URLEncoder.encode(token, "UTF-8")
        val request = Request.Builder().url(url).get().apply {
            hostHeader?.let { header("Host", it) }
        }.build()
        try {
            exchanger.newCall(request).execute().use { response ->
                // A minting response can also carry deletion cookies for other authorities; only
                // the header naming a value is the granted session.
                val granted = response.headers("Set-Cookie").firstOrNull { header ->
                    header.startsWith(COOKIE_PREFIX) &&
                        header.substringBefore(';').substringAfter('=', "").isNotEmpty()
                }
                if (granted == null) return@use SessionExchange.Refused(response.code)
                val now = System.currentTimeMillis()
                SessionExchange.Granted(
                    // Only the name=value pair travels back; `Path`, `HttpOnly` and `SameSite`
                    // are instructions to a browser, and repeating them on a request would be
                    // sending the server its own directives.
                    cookie = granted.substringBefore(';'),
                    expiresAtMillis = sessionCookieExpiryMillis(granted, now),
                )
            }
        } catch (e: IOException) {
            SessionExchange.Unreachable(TransportFailures.classify(e), e.message)
        }
    }

    /**
     * Absolute expiry declared by one granted `Set-Cookie`, or null when it declares none.
     *
     * `Max-Age` wins over `Expires`: it is the harness's own countdown and needs no date parsing.
     * Older hosts may declare only `Expires`; a host that declares neither still works, it just
     * cannot be refreshed proactively.
     */
    internal fun sessionCookieExpiryMillis(setCookie: String, nowMillis: Long): Long? {
        var maxAgeSeconds: Long? = null
        var expires: String? = null
        for (attribute in setCookie.split(';').drop(1)) {
            val at = attribute.indexOf('=')
            if (at < 0) continue
            val name = attribute.substring(0, at).trim()
            val value = attribute.substring(at + 1).trim()
            when {
                name.equals("Max-Age", ignoreCase = true) -> maxAgeSeconds = value.toLongOrNull()
                name.equals("Expires", ignoreCase = true) -> expires = value
            }
        }
        maxAgeSeconds?.let { seconds ->
            if (seconds <= 0L) return nowMillis
            val bounded = seconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L
            return if (bounded > Long.MAX_VALUE - nowMillis) null else nowMillis + bounded
        }
        return expires?.let { date ->
            runCatching {
                ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }

    /**
     * Pull a launch token out of whatever the user pasted.
     *
     * People copy the whole startup line, the whole URL, or just the token. Accepting all three
     * costs one function and removes the one step where a working token gets rejected for having
     * a `?token=` in front of it.
     */
    fun tokenFrom(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val marker = trimmed.indexOf("token=")
        val candidate = if (marker >= 0) {
            trimmed.substring(marker + "token=".length).takeWhile { it != '&' && !it.isWhitespace() }
        } else {
            trimmed.takeWhile { !it.isWhitespace() }
        }
        return candidate.takeIf { it.isNotEmpty() }
    }
}
