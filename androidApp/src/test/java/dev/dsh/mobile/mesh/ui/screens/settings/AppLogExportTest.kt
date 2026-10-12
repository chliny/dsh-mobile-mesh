package dev.dsh.mobile.mesh.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogExportTest {
    @Test
    fun `time range includes logs from prior process IDs and excludes records outside the interval`() {
        val output = """--------- beginning of main
1700000000.000 10044 410 410 D Mesh: before range
1700000001.000 10044 410 410 D Mesh: previous process
1700000002.500 10044 988 988 E Mesh: current process
1700000003.000 10044 988 988 D Mesh: at exclusive end
"""

        val selected = filterAppLogsByTimeRange(
            logcatOutput = output,
            range = AppLogTimeRange(startEpochMs = 1_700_000_001_000, endEpochMs = 1_700_000_003_000),
        )

        assertEquals(
            """1700000001.000 10044 410 410 D Mesh: previous process
1700000002.500 10044 988 988 E Mesh: current process
""",
            selected,
        )
    }

    @Test
    fun `invalid or empty ranges return no log records`() {
        val output = "1700000001.000 10044 410 410 D Mesh: record\n"

        assertEquals("", filterAppLogsByTimeRange(output, AppLogTimeRange(2_000, 1_000)))
        assertEquals("", filterAppLogsByTimeRange(output, AppLogTimeRange(1_700_000_002_000, 1_700_000_003_000)))
    }
}
