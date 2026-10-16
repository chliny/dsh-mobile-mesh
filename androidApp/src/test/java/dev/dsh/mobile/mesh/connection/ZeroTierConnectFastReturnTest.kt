package dev.dsh.mobile.mesh.connection

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the vendored native fast path alongside the Pixel 3 timing regression. */
class ZeroTierConnectFastReturnTest {
    @Test
    fun `java socket passes recovery timeout to native connect`() {
        val source = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "third_party/libzt/src/bindings/java/com/zerotier/sockets/ZeroTierSocket.java") }
            .firstOrNull { it.isFile }
            ?: error("Cannot locate vendored ZeroTierSocket.java")
        val binding = source.readText()
            .substringAfter("public void connect(InetAddress remoteAddr, int remotePort, int timeoutMs)")
            .substringBefore("public void connect(String remoteAddr, int remotePort)")
        assertTrue("native connect must receive the explicit recovery budget", binding.contains("remotePort, timeoutMs"))
    }

    @Test
    fun `explicit native timeout uses nonblocking poll and restores blocking mode`() {
        val source = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "third_party/libzt/src/Sockets.cpp") }
            .firstOrNull { it.isFile }
            ?: error("Cannot locate vendored libzt Sockets.cpp")
        val connect = source.readText().substringAfter("int zts_connect(int fd, const char* ipstr, unsigned short port, int timeout_ms)")
            .substringBefore("int zts_bind(")
        val bounded = connect.substringAfter("if (explicit_timeout) {").substringBefore("if (zts_get_blocking(fd))")
        assertTrue("native connect must be nonblocking before the handshake", bounded.indexOf("ZTS_O_NONBLOCK") < bounded.indexOf("err = zts_bsd_connect(fd, sa, addrlen);") && bounded.contains("ZTS_O_NONBLOCK"))
        assertTrue("wait for the socket only within a monotonic deadline", bounded.contains("steady_clock::now()") && bounded.contains("zts_bsd_poll(&pollfd, 1, static_cast<int>(remaining))"))
        assertTrue("a writable socket still needs SO_ERROR verification", bounded.contains("ZTS_SO_ERROR"))
        assertTrue("restore blocking mode before handing socket to SSH", bounded.contains("ZTS_F_SETFL, original_flags"))
    }

    @Test
    fun `target probe logs native connect budget result code and cleanup duration`() {
        val source = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "androidApp/src/main/java/dev/dsh/mobile/mesh/connection/ZeroTierConnector.kt") }
            .firstOrNull { it.isFile }
            ?: error("Cannot locate ZeroTierConnector.kt")
        val dial = source.readText().substringAfter("private fun dialTarget(")
            .substringBefore("private fun resolveTarget(")
        assertTrue("log the exact timeout passed to native connect", dial.contains("budgetMs=") && dial.contains("remainingMs"))
        assertTrue("log time spent inside native connect separately", dial.contains("nativeConnectMs=") && dial.contains("nativeConnectMs"))
        assertTrue("log the native error code when present", dial.contains("errorCode=") && dial.contains("nativeError"))
        assertTrue("log socket close duration separately", dial.contains("socketCloseMs=") && dial.contains("socketCloseMs"))
    }

    @Test
    fun `successful native connect returns before retry delay`() {
        val source = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "third_party/libzt/src/Sockets.cpp") }
            .firstOrNull { it.isFile }
            ?: error("Cannot locate vendored libzt Sockets.cpp")
        val connect = source.readText().substringAfter("int zts_connect(int fd, const char* ipstr, unsigned short port, int timeout_ms)")
            .substringBefore("int zts_bind(")
        val handshake = connect.indexOf("err = zts_bsd_connect(fd, sa, addrlen);")
        val earlyReturn = connect.indexOf("if (err >= 0) {\n                    return err;\n                }", handshake)
        val retryDelay = connect.indexOf("zts_util_delay(connect_delay);", handshake)
        assertTrue("native connect call must be present", handshake >= 0)
        assertTrue("successful connect must bypass the 250ms retry delay", earlyReturn > handshake && earlyReturn < retryDelay)
    }
}
