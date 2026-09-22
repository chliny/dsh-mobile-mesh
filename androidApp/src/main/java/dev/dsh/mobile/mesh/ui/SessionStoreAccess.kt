package dev.dsh.mobile.mesh.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.dsh.mobile.mesh.connection.HostsStore
import dev.dsh.mobile.mesh.data.ChatDraftStore
import dev.dsh.mobile.mesh.data.SessionStore
import dev.dsh.mobile.mesh.data.WorkspaceFilesStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for the process-scoped [SessionStore].
 *
 * The store is a [javax.inject.Singleton], not a [androidx.lifecycle.ViewModel] — it outlives every
 * screen and owns the live mirror of the connected harness — so composables resolve it through an
 * app-scoped entry point rather than `hiltViewModel()`. One declaration serves every screen;
 * duplicating it per file would mean several Hilt-generated accessors for one singleton.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionStoreEntryPoint {
    fun sessionStore(): SessionStore
    fun chatDraftStore(): ChatDraftStore
    fun workspaceFilesStore(): WorkspaceFilesStore
    fun hostsStore(): HostsStore
    fun appNavigationState(): AppNavigationState
}

@Composable
internal fun rememberChatDraftStore(): ChatDraftStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).chatDraftStore()
    }
}

@Composable
internal fun rememberAppNavigationState(): AppNavigationState {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).appNavigationState()
    }
}

/** Resolves the process-scoped [SessionStore] once per composition. */
@Composable
internal fun rememberWorkspaceFilesStore(): WorkspaceFilesStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).workspaceFilesStore()
    }
}

@Composable
internal fun rememberSessionStore(): SessionStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).sessionStore()
    }
}

/**
 * Resolves the process-scoped [HostsStore] once per composition.
 *
 * For the handful of preferences a screen owns outright — drawer sort order, for instance — where
 * routing through a ViewModel would add a layer that only forwards.
 */
@Composable
internal fun rememberHostsStore(): HostsStore {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SessionStoreEntryPoint::class.java).hostsStore()
    }
}
