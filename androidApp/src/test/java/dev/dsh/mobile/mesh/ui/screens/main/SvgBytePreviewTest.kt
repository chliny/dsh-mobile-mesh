package dev.dsh.mobile.mesh.ui.screens.main

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SvgBytePreviewTest {
    @Test fun `SVG data URI preserves exact server bytes beyond the text page line limit`() {
        val bytes = ("<svg>\n" + "<path/>\n".repeat(5_001) + "</svg>").toByteArray()
        val url = svgDataUrl(bytes)
        assertArrayEquals(bytes, Base64.getDecoder().decode(url.removePrefix("data:image/svg+xml;base64,")))
    }
}
