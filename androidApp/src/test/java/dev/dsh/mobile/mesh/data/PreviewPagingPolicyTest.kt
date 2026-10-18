package dev.dsh.mobile.mesh.data

import dev.dsh.mobile.mesh.core.wire.RpcError
import dev.dsh.mobile.mesh.core.wire.dto.WorkspaceFileText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPagingPolicyTest {
    private val partial = PreviewState.Text(WorkspaceFileText(
        absolutePath = "/workspace/a.md", version = "v1", offset = 1,
        text = "first page", lines = 1, eof = false,
    ))

    @Test
    fun `failed next page retains accepted server text and exposes retryable error`() {
        val state = WorkspaceFilesState(
            workspaceKey = "w", preview = partial,
            previewPath = "a.md", previewSessionId = "s1", previewLoadingMore = true,
        )
        val failed = state.withPreviewError(RpcError("timeout", "page unavailable"), append = true)
            .copy(previewLoadingMore = false)
        assertEquals(partial, failed.preview)
        assertEquals("page unavailable", failed.previewPageError)
        assertFalse(failed.canLoadNextPreviewPage("s1", "a.md", hasApi = true))
        assertTrue(failed.copy(previewPageError = null).canLoadNextPreviewPage("s1", "a.md", hasApi = true))
        assertTrue(state.withPreviewError(RpcError("timeout", "page unavailable"), append = false).preview is PreviewState.Failed)
    }

    @Test
    fun `disconnected and replaced previews must not enter loading more state`() {
        val state = WorkspaceFilesState(
            workspaceKey = "w", preview = partial,
            previewPath = "a.md", previewSessionId = "s1",
        )
        assertFalse(state.canLoadNextPreviewPage("s1", "a.md", hasApi = false))
        assertFalse(state.canLoadNextPreviewPage("s1", "b.md", hasApi = true))
        assertFalse(state.canLoadNextPreviewPage("s2", "a.md", hasApi = true))
        assertTrue(state.canLoadNextPreviewPage("s1", "a.md", hasApi = true))
        assertFalse(state.copy(previewLoadingMore = true).canLoadNextPreviewPage("s1", "a.md", hasApi = true))
    }
}
