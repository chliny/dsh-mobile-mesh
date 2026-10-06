package dev.dsh.mobile.mesh.connection

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the vendored native fast path alongside the Pixel 3 timing regression. */
class ZeroTierConnectFastReturnTest {
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
