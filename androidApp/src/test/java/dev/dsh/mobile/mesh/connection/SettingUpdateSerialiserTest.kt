package dev.dsh.mobile.mesh.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingUpdateSerialiserTest {
    @Test
    fun `concurrent read modify write operations retain both updates and mirror committed theme`() = runTest {
        val serialiser = SettingUpdateSerialiser()
        var settings = AppSettings()
        var mirroredTheme = settings.themePreference
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async {
            serialiser.run {
                firstEntered.complete(Unit)
                releaseFirst.await()
                settings = settings.copy(themePreference = "dark")
                mirroredTheme = settings.themePreference
            }
        }
        firstEntered.await()
        val second = async {
            serialiser.run {
                settings = settings.copy(notifyGoal = false)
                mirroredTheme = settings.themePreference
            }
        }
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals("dark", settings.themePreference)
        assertEquals(false, settings.notifyGoal)
        assertEquals(settings.themePreference, mirroredTheme)
    }
}
