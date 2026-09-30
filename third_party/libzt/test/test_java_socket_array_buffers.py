"""Source-level regression guard for blocking Java socket JNI array transfers.

Run without an Android runtime: python3 -m unittest third_party/libzt/test/test_java_socket_array_buffers.py
"""

from pathlib import Path
import re
import unittest


SOURCE = (Path(__file__).resolve().parents[1] / "src/bindings/java/JavaSockets.cxx").read_text()


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
