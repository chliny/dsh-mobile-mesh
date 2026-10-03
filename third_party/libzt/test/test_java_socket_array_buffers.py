"""Source-level regression guard for blocking Java socket JNI array transfers.

Run without an Android runtime: python3 -m unittest third_party/libzt/test/test_java_socket_array_buffers.py
"""

from pathlib import Path
import re
import unittest


SOURCE = (Path(__file__).resolve().parents[1] / "src/bindings/java/JavaSockets.cxx").read_text()
INPUT_STREAM = (Path(__file__).resolve().parents[1] / "src/bindings/java/com/zerotier/sockets/ZeroTierInputStream.java").read_text()


def function_body(name):
    match = re.search(r"\b" + re.escape(name) + r"\([^{};]*\)\s*\{", SOURCE)
    assert match, name
    start = match.end()
    depth = 1
    for position in range(start, len(SOURCE)):
        if SOURCE[position] == "{":
            depth += 1
        elif SOURCE[position] == "}":
            depth -= 1
            if depth == 0:
                return SOURCE[start:position]
    raise AssertionError(f"unterminated function: {name}")


class JavaSocketArrayBufferTest(unittest.TestCase):
    def test_stream_variants_use_unpinned_native_buffers(self):
        for variant, helper in (
            ("zts_1bsd_1read", "read_socket_buffer"),
            ("zts_1bsd_1read_1offset", "read_socket_buffer"),
            ("zts_1bsd_1read_1length", "read_socket_buffer"),
            ("zts_1bsd_1write", "write_socket_buffer"),
            ("zts_1bsd_1write_1offset", "write_socket_buffer"),
        ):
            with self.subTest(variant=variant):
                body = function_body("Java_com_zerotier_sockets_ZeroTierNative_" + variant)
                self.assertIn(helper + "(env, fd, buf,", body)
                self.assertNotIn("GetPrimitiveArrayCritical", body)

    def test_read_preserves_service_error_instead_of_encoding_it_as_eof(self):
        read = function_body("read_socket_buffer")
        self.assertIn("if (retval == ZTS_ERR_SERVICE)", read)
        self.assertLess(read.index("if (retval == ZTS_ERR_SERVICE)"), read.index("const int saved_errno"))
        self.assertIn("free(data);", read)
        self.assertIn("return ZTS_ERR_SERVICE;", read)
        self.assertIn("return retval > -1 ? retval : -saved_errno;", read)
        self.assertLess(read.index("errno = 0;"), read.index("zts_bsd_read(fd, data, len)"))
        self.assertIn("errno != 0 ? errno : EIO", read)
        self.assertNotIn("retval < 0 ? zts_errno", read)

    def test_write_preserves_thread_local_failure_instead_of_global_socket_errno(self):
        write = function_body("write_socket_buffer")
        self.assertLess(write.index("errno = 0;"), write.index("zts_bsd_write(fd, data, len)"))
        self.assertIn("retval < 0 ? (errno != 0 ? errno : EIO) : 0", write)
        self.assertNotIn("retval < 0 ? zts_errno", write)

    def test_single_byte_write_also_uses_thread_local_errno(self):
        write = function_body("Java_com_zerotier_sockets_ZeroTierNative_zts_1bsd_1write_1byte")
        self.assertLess(write.index("errno = 0;"), write.index("zts_bsd_write(fd, &buf, 1)"))
        self.assertIn("const int saved_errno = retval < 0 ? (errno != 0 ? errno : EIO) : 0", write)
        self.assertNotIn("return retval > -1 ? retval : -(zts_errno);", write)

    def test_keepalive_idle_option_jni_forwards_integer_value(self):
        binding = (Path(__file__).resolve().parents[1] / "src/bindings/java/com/zerotier/sockets/ZeroTierNative.java").read_text()
        socket_api = (Path(__file__).resolve().parents[1] / "src/bindings/java/com/zerotier/sockets/ZeroTierSocket.java").read_text()
        self.assertIn("zts_bsd_setsockopt_int", binding)
        self.assertIn("setTcpKeepIdle", socket_api)
        body = function_body("Java_com_zerotier_sockets_ZeroTierNative_zts_1bsd_1setsockopt_1int")
        self.assertIn("zts_bsd_setsockopt(fd, level, option, &value, sizeof(value))", body)

    def test_input_stream_does_not_treat_connection_reset_as_timeout(self):
        self.assertIn("public static int ZTS_EINTR = 4;", (Path(__file__).resolve().parents[1] / "src/bindings/java/com/zerotier/sockets/ZeroTierNative.java").read_text())
        self.assertIn("public static int ZTS_ECONNRESET = 104;", (Path(__file__).resolve().parents[1] / "src/bindings/java/com/zerotier/sockets/ZeroTierNative.java").read_text())
        self.assertEqual(INPUT_STREAM.count("if (retval == -11)"), 6)
        self.assertNotIn("retval == -104", INPUT_STREAM)
        self.assertIn('throw new IOException("skip(), errno=" + retval)', INPUT_STREAM)
        self.assertEqual(INPUT_STREAM.count("if (retval == -4)"), 0)
        for operation in ("read()", "read(destBuffer)", "read(destBuffer, offset, numBytes)", "readAllBytes()", "readNBytes(destBuffer, offset, numBytes)"):
            with self.subTest(operation=operation):
                self.assertIn('throw new IOException("' + operation + ', errno=" + retval)', INPUT_STREAM)

    def test_datagram_receive_preserves_reset_and_only_times_out_for_retryable_errno(self):
        datagram = (Path(__file__).resolve().parents[1] / "src/bindings/java/com/zerotier/sockets/ZeroTierDatagramSocket.java").read_text()
        self.assertIn("if (bytesRead == -11)", datagram)
        self.assertNotIn("bytesRead == -4", datagram)
        self.assertIn('throw new IOException("read(DatagramPacket), errno=" + bytesRead)', datagram)
        self.assertNotIn("bytesRead == -104", datagram)

    def test_native_buffers_validate_range_and_copy_only_completed_reads(self):
        allocation = function_body("allocate_socket_buffer")
        self.assertIn("len > array_len - offset", allocation)
        self.assertIn("offset < 0 || len < 0", allocation)
        self.assertIn("malloc(len > 0 ? static_cast<size_t>(len) : 1)", allocation)
        for name, region_call in (("read_socket_buffer", "SetByteArrayRegion"),
                                  ("write_socket_buffer", "GetByteArrayRegion")):
            with self.subTest(name=name):
                body = function_body(name)
                self.assertIn("allocate_socket_buffer", body)
                self.assertIn(region_call, body)
                self.assertIn("free(data)", body)
                self.assertNotIn("GetPrimitiveArrayCritical", body)
                self.assertLess(body.index("allocate_socket_buffer"), body.index("zts_bsd_"))
        read = function_body("read_socket_buffer")
        self.assertRegex(read, r"if \(retval > 0\)\s*\{\s*env->SetByteArrayRegion\(buf, offset, retval, data\)")
        write = function_body("write_socket_buffer")
        self.assertLess(write.index("GetByteArrayRegion"), write.index("zts_bsd_write"))
        self.assertIn("if (env->ExceptionCheck())", write)


if __name__ == "__main__":
    unittest.main()
