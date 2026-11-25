package dev.dsh.mobile.mesh.core.wire

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Host's authenticated `GET /api/file` route, the endpoint the web client renders workspace
 * images with.
 *
 * The web resolves a Markdown image to its absolute path and loads `/api/file?path=…`; the route
 * answers the bytes with their MIME type after a `302` to a versioned, immutable URL. The app used
 * to reach workspace images only through the `workspaceFiles/readBytes` Remote, which is bounded by
 * the file service's byte window and needs the Session scope to resolve; a failure there is silent
 * and leaves the transcript with a caption instead of the image. Reading the route the web reads
 * keeps both clients on one contract and lifts the app's own byte cap.
 *
 * Redirects are followed here rather than by the shared HTTP client, because the signed session
 * cookie and the relay `Host` header must travel with the versioned URL too.
 */
class HarnessFileApi(
    baseUrl: String,
    private val cookie: String?,
    private val hostHeader: String?,
    httpClient: OkHttpClient,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val base = URL(baseUrl.trimEnd('/') + "/")
    private val client = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Bytes of the image at [absolutePath], or null when the Host has no file route, refuses the
     * request, answers a non-image type, or the body exceeds [maxBytes].
     */
    suspend fun readImage(absolutePath: String): ByteArray? = withContext(Dispatchers.IO) {
        var target = URL(base, "api/file?path=" + URLEncoder.encode(absolutePath, "UTF-8"))
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder().url(target).get().apply {
                hostHeader?.let { header("Host", it) }
                cookie?.let { header("Cookie", it) }
            }.build()
            val response = try {
                client.newCall(request).execute()
            } catch (_: IOException) {
                return@withContext null
            }
            response.use {
                if (it.isRedirect) {
                    val location = it.header("Location") ?: return@withContext null
                    val next = try {
                        resolveRedirect(target, location)
                    } catch (_: IOException) {
                        return@withContext null
                    }
                    // The signed session cookie must never travel to an origin it was not issued for.
                    if (!sameOrigin(target, next)) return@withContext null
                    target = next
                    return@repeat
                }
                if (!it.isSuccessful) return@withContext null
                if (!it.header("Content-Type").orEmpty().startsWith("image/", ignoreCase = true)) return@withContext null
                val body = it.body ?: return@withContext null
                if (body.contentLength() > maxBytes) return@withContext null
                return@withContext body.byteStream().use { stream -> readBounded(stream) }
            }
        }
        null
    }

    /**
     * Resolve the Host's redirect.
     *
     * The route answers `Location: ?path=…&v=…`: a query-only reference that keeps the request
     * path. Java's URL constructor resolves that form against the *directory* (`…/file` becomes
     * `…/`), so it is handled explicitly; every other form is an ordinary relative reference.
     */
    private fun resolveRedirect(current: URL, location: String): URL =
        if (location.startsWith("?")) {
            URL(current.protocol, current.host, current.port, current.file.substringBefore('?') + location)
        } else {
            URL(current, location)
        }

    private fun sameOrigin(left: URL, right: URL): Boolean =
        left.protocol.equals(right.protocol, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(url: URL): Int = if (url.port != -1) url.port else url.defaultPort

    private fun readBounded(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private companion object {
        /** One hop is the normal `302`; the second keeps a proxy rewrite from looping forever. */
        const val MAX_REDIRECTS = 2

        /** The Host caps image reads (`attachments.imageLimits.maxImageBytes`); this is a backstop. */
        const val DEFAULT_MAX_BYTES = 12 * 1024 * 1024
    }
}
