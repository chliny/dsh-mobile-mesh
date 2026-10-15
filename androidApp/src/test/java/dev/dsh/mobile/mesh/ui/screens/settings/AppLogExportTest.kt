package dev.dsh.mobile.mesh.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.junit.Test

class AppLogExportTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `archive retains prior process logs after device buffer rolls over`() {
        val directory = files.newFolder("archive")
        val priorProcess = AppLogArchive(directory, segmentBytes = 100, maxSegments = 3)
        priorProcess.append("1700000001.000 10044 410 410 D Mesh: first process")
        priorProcess.append("1700000002.000 10044 410 410 D Mesh: more history")

        val nextProcess = AppLogArchive(directory, segmentBytes = 100, maxSegments = 3)
        val resume = LogcatResume(nextProcess.lastLine())
        assertFalse(resume.shouldAppend("1700000002.000 10044 410 410 D Mesh: more history"))
        val nextLine = "1700000003.000 10044 988 988 E Mesh: new process"
        assertTrue(resume.shouldAppend(nextLine))
        nextProcess.append(nextLine)

        assertEquals(
            "1700000001.000 10044 410 410 D Mesh: first process\n" +
                "1700000002.000 10044 410 410 D Mesh: more history\n" + nextLine + "\n",
            nextProcess.read(AppLogTimeRange(1_700_000_000_000, 1_700_000_004_000)),
        )
    }

    @Test
    fun `bounded archive rotates oldest segments without deleting the recent history`() {
        val archive = AppLogArchive(files.newFolder("rotated"), segmentBytes = 55, maxSegments = 2)
        (1..4).forEach { archive.append("170000000$it.000 10044 410 410 D Mesh: record $it") }
        val result = archive.read(AppLogTimeRange(1_700_000_000_000, 1_700_000_005_000))
        assertFalse(result.contains("record 1"))
        assertFalse(result.contains("record 2"))
        assertTrue(result.contains("record 3"))
        assertTrue(result.contains("record 4"))
    }

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
    fun `epoch parsing preserves millisecond boundaries without floating point truncation`() {
        assertEquals(1_700_000_002_999L, logEpochMillis("1700000002.999 10044 410 410 D Mesh: boundary"))
        assertEquals(1_700_000_003_000L, logEpochMillis("1700000003.000 10044 410 410 D Mesh: end"))
    }

    @Test
    fun `invalid or empty ranges return no log records`() {
        val output = "1700000001.000 10044 410 410 D Mesh: record\n"

        assertEquals("", filterAppLogsByTimeRange(output, AppLogTimeRange(2_000, 1_000)))
        assertEquals("", filterAppLogsByTimeRange(output, AppLogTimeRange(1_700_000_002_000, 1_700_000_003_000)))
    }
}
