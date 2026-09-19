package dev.dsh.mobile.mesh.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Test

class TodoProgressLabelTest {
    @Test
    fun `formats nonzero task status counts like Web`() {
        assertEquals(
            "1 进行中\u2002·\u20022 待处理",
            todoProgressLabel(
                completed = 0,
                inProgress = 1,
                pending = 2,
                completedLabel = "3 已完成",
                inProgressLabel = "1 进行中",
                pendingLabel = "2 待处理",
            ),
        )
    }

    @Test
    fun `omits zero status counts`() {
        assertEquals(
            "2 已完成",
            todoProgressLabel(
                completed = 2,
                inProgress = 0,
                pending = 0,
                completedLabel = "2 已完成",
                inProgressLabel = "0 进行中",
                pendingLabel = "0 待处理",
            ),
        )
    }
}
