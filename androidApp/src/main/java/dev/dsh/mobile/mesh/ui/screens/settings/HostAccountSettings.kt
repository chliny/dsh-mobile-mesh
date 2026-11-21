package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.dsh.mobile.mesh.BuildConfig
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.AccountClientMetadata
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostAccountBalanceResult
import dev.dsh.mobile.mesh.core.wire.HostAccountProfileResult
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.accountGetBalance
import dev.dsh.mobile.mesh.core.wire.accountGetProfile
import dev.dsh.mobile.mesh.core.wire.accountGetState
import dev.dsh.mobile.mesh.ui.components.DsButton
import dev.dsh.mobile.mesh.ui.components.DsButtonSize
import dev.dsh.mobile.mesh.ui.components.DsButtonVariant
import dev.dsh.mobile.mesh.ui.theme.DsTheme
import dev.dsh.mobile.mesh.ui.theme.DsType
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException

/**
 * Read-only Host account card. The caller must provide the active authenticated connection's API
 * and remove this card immediately on disconnect or connection switch; there is no Platform call,
 * credential access, local account cache, login, sign-out, or payment action here.
 */
@Composable
fun HostAccountSettings(api: DshApiClient, modifier: Modifier = Modifier) {
    key(api) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var refresh by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var accountStatus by remember { mutableStateOf<String?>(null) }
        var profile by remember { mutableStateOf<HostAccountProfileResult?>(null) }
        var balance by remember { mutableStateOf<HostAccountBalanceResult?>(null) }
        var stateUnavailable by remember { mutableStateOf(false) }
        var profileUnavailable by remember { mutableStateOf(false) }
        var balanceUnavailable by remember { mutableStateOf(false) }

        LaunchedEffect(api, lifecycle, refresh) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loading = true
                accountStatus = null
                profile = null
                balance = null
                stateUnavailable = false
                profileUnavailable = false
                balanceUnavailable = false
                try {
                    when (val state = api.accountGetState()) {
                        is RpcResult.Err -> stateUnavailable = true
                        is RpcResult.Ok -> {
                            accountStatus = state.value.status
                            if (state.value.status == "credential-stored") {
                                val client = AccountClientMetadata(
                                    version = BuildConfig.VERSION_NAME,
                                    locale = Locale.getDefault().toLanguageTag(),
                                    timezoneOffsetSeconds = TimeZone.getDefault()
                                        .getOffset(System.currentTimeMillis()) / 1_000,
                                )
                                try {
                                    when (val result = api.accountGetProfile(client)) {
                                        is RpcResult.Ok -> profile = result.value
                                        is RpcResult.Err -> profileUnavailable = true
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    profileUnavailable = true
                                }
                                // A profile failure must not mask a separately available wallet result.
                                try {
                                    when (val result = api.accountGetBalance(client)) {
                                        is RpcResult.Ok -> balance = result.value
                                        is RpcResult.Err -> balanceUnavailable = true
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    balanceUnavailable = true
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    stateUnavailable = true
                } finally {
                    loading = false
                }
            }
        }

        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.account_host_title), style = DsType.small13, color = DsTheme.colors.labelPrimary)
            when {
                loading -> Text(stringResource(R.string.account_host_loading), style = DsType.caption11)
                stateUnavailable -> Text(stringResource(R.string.account_host_unavailable), style = DsType.caption11)
                accountStatus == "signed-out" -> Text(stringResource(R.string.account_host_signed_out), style = DsType.caption11)
                accountStatus == "credential-stored" -> {
                    Text(stringResource(R.string.account_host_status_stored), style = DsType.caption11)
                    Text(stringResource(R.string.account_host_profile), style = DsType.small13)
                    if (profile?.status == "ready") {
                        // Contact has already been masked by Platform. Neither id nor avatar URL is rendered.
                        val display = listOfNotNull(profile?.value?.name, profile?.value?.contact)
                            .filter { it.isNotBlank() }
                        if (display.isEmpty()) Text(stringResource(R.string.account_host_unavailable), style = DsType.caption11)
                        else display.forEach { Text(it, style = DsType.caption11) }
                    } else if (profileUnavailable || profile == null || profile?.status == "failed") {
                        Text(stringResource(R.string.account_host_unavailable), style = DsType.caption11)
                    }
                    Text(stringResource(R.string.account_host_balance), style = DsType.small13)
                    val wallets = balance?.takeIf { it.status == "ready" }
                    when {
                        wallets != null -> {
                            if (wallets.value.isEmpty()) Text(stringResource(R.string.account_host_no_balance), style = DsType.caption11)
                            wallets.value.forEach { Text("${it.balance} ${it.currency}", style = DsType.caption11) }
                            if (wallets.bonusWallets.isNotEmpty()) {
                                Text(stringResource(R.string.account_host_bonus_balance), style = DsType.small13)
                                wallets.bonusWallets.forEach { Text("${it.balance} ${it.currency}", style = DsType.caption11) }
                            }
                        }
                        balanceUnavailable || balance == null || balance?.status == "failed" ->
                            Text(stringResource(R.string.account_host_unavailable), style = DsType.caption11)
                    }
                }
                else -> Text(stringResource(R.string.account_host_unavailable), style = DsType.caption11)
            }
            DsButton(
                text = stringResource(R.string.account_host_refresh),
                onClick = { refresh++ },
                enabled = !loading,
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
        }
    }
}
