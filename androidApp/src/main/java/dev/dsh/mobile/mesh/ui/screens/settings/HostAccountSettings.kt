package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.dsh.mobile.mesh.BuildConfig
import dev.dsh.mobile.mesh.R
import dev.dsh.mobile.mesh.core.wire.AccountClientMetadata
import dev.dsh.mobile.mesh.core.wire.DshApiClient
import dev.dsh.mobile.mesh.core.wire.HostAccountBalanceResult
import dev.dsh.mobile.mesh.core.wire.HostAccountProfileResult
import dev.dsh.mobile.mesh.core.wire.HostAccountWallet
import dev.dsh.mobile.mesh.core.wire.RpcResult
import dev.dsh.mobile.mesh.core.wire.accountGetBalance
import dev.dsh.mobile.mesh.core.wire.accountGetProfile
import dev.dsh.mobile.mesh.core.wire.accountGetState
import dev.dsh.mobile.mesh.ui.theme.DsSpacing
import dev.dsh.mobile.mesh.ui.theme.DsType
import java.math.RoundingMode
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

        LaunchedEffect(api, lifecycle, refresh) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loading = true
                accountStatus = null
                profile = null
                balance = null
                stateUnavailable = false
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
                                        is RpcResult.Err -> Unit
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Leave the profile unavailable and still load the wallets.
                                }
                                // A profile failure must not mask a separately available wallet result.
                                try {
                                    when (val result = api.accountGetBalance(client)) {
                                        is RpcResult.Ok -> balance = result.value
                                        is RpcResult.Err -> Unit
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Leave the wallets unavailable without discarding the profile.
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

        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpacing.xsmall)) {
            when {
                loading -> Text(stringResource(R.string.account_host_loading), style = DsType.caption11)
                stateUnavailable -> Text(stringResource(R.string.account_host_unavailable), style = DsType.caption11)
                accountStatus == "signed-out" -> Text(stringResource(R.string.account_host_signed_out), style = DsType.caption11)
                accountStatus == "credential-stored" -> {
                    Text(stringResource(R.string.account_host_status_stored), style = DsType.caption11)
                    val unavailable = stringResource(R.string.account_host_unavailable)
                    // Platform already masks the contact. Never render the account id or avatar URL.
                    val readyProfile = profile?.takeIf { it.status == "ready" }?.value
                    val name = readyProfile?.name?.takeIf { it.isNotBlank() }
                    val contact = readyProfile?.contact?.takeIf { it.isNotBlank() }
                    val profileValue = when {
                        name != null && contact != null -> "$name($contact)"
                        else -> name ?: contact ?: unavailable
                    }
                    AccountInfoRow(stringResource(R.string.account_host_profile), profileValue, singleLine = true)

                    val wallets = balance?.takeIf { it.status == "ready" }
                    val balanceValue = when {
                        wallets == null -> unavailable
                        wallets.value.isEmpty() -> stringResource(R.string.account_host_no_balance)
                        else -> wallets.value.joinToString(" · ") { it.displayAmount() }
                    }
                    AccountInfoRow(stringResource(R.string.account_host_balance), balanceValue)
                    if (wallets != null && wallets.bonusWallets.isNotEmpty()) {
                        AccountInfoRow(
                            stringResource(R.string.account_host_bonus_balance),
                            wallets.bonusWallets.joinToString(" · ") { it.displayAmount() },
                        )
                    }
                }
                else -> Text(stringResource(R.string.account_host_unavailable), style = DsType.caption11)
            }
            IconButton(onClick = { refresh++ }, enabled = !loading) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.account_host_refresh),
                )
            }
        }
    }
}

private fun HostAccountWallet.displayAmount(): String {
    val amount = balance.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: balance
    return "$amount $currency"
}

@Composable
private fun AccountInfoRow(label: String, value: String, singleLine: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = DsType.small13)
        Text(
            value,
            Modifier.weight(2f),
            style = DsType.caption11,
            textAlign = TextAlign.End,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}
