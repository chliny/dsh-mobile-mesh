package dev.dsh.mobile.mesh.ui.screens.main

import dev.dsh.mobile.mesh.core.wire.dto.ScheduleCatalogEntry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScheduleScreenTest {
    @Test fun expectedRecordOmitsCatalogBindingAndStatus() {
        val task = ScheduleCatalogEntry(
            id = "task-1", kind = "weekly", title = "Backup", prompt = "Back up files",
            scheduledAt = "2026-10-19T01:00:00.000Z", sessionId = "session-1", status = "active",
            time = "09:00:00.000", timeZone = "Asia/Shanghai", weekdays = listOf(1, 5),
        )
        val expected = task.expectedRecord()
        assertEquals("weekly", expected.getValue("kind").jsonPrimitive.content)
        assertEquals("Backup", expected.getValue("title").jsonPrimitive.content)
        assertFalse(expected.containsKey("status"))
        assertFalse(expected.containsKey("sessionId"))
        assertFalse(expected.containsKey("lastDelivery"))
        assertEquals("[1,5]", expected.getValue("weekdays").toString())
    }

    @Test fun dailyWeeklyAndCronRulesUseHostSelectorFields() {
        assertEquals("daily", scheduleTimingChange("daily", "09:00:00", "Asia/Shanghai", "")?.getValue("kind")?.jsonPrimitive?.content)
        val weekly = scheduleTimingChange("weekly", "09:00:00", "UTC", "5,1")!!
        assertEquals("[1,5]", (weekly.getValue("weekly") as kotlinx.serialization.json.JsonObject).getValue("weekdays").toString())
        assertEquals("cron", scheduleTimingChange("cron", "0 9 * * 1-5", "UTC", "")?.getValue("kind")?.jsonPrimitive?.content)
        assertEquals(null, scheduleTimingChange("weekly", "09:00:00", "UTC", "1,1"))
        assertEquals(null, scheduleTimingChange("every", "59", "UTC", ""))
    }

    @Test fun expectedRecordKeepsEveryRuleInterval() {
        val task = ScheduleCatalogEntry("task-2", "every", "Ping", "Check", "2026-10-19T01:00:00.000Z", "session-1", "inactive", everySeconds = 300)
        assertEquals(JsonPrimitive(300), task.expectedRecord().getValue("everySeconds"))
    }
}
