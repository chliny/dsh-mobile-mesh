package dev.dsh.mobile.mesh.core.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The Host derives Platform request headers; the app must never contact Platform directly. */
@Serializable
data class AccountClientMetadata(
    val version: String,
    val locale: String,
    /** Seconds east of Greenwich, including the current daylight-saving offset. */
    val timezoneOffsetSeconds: Int,
)

/** Safe Host projection. Links and login attempts are deliberately not decoded or rendered. */
@Serializable
data class HostAccountView(val status: String)

/** Platform-masked display data; never display the account id or avatar URL. */
@Serializable
data class HostAccountProfile(
    val name: String? = null,
    val contact: String? = null,
)

@Serializable
data class HostAccountProfileResult(
    val status: String,
    val value: HostAccountProfile? = null,
)

/** Decimal balances remain strings: floating-point parsing would lose server precision. */
@Serializable
data class HostAccountWallet(val currency: String, val balance: String)

@Serializable
data class HostAccountBalanceResult(
    val status: String,
    val value: List<HostAccountWallet> = emptyList(),
    val bonusWallets: List<HostAccountWallet> = emptyList(),
)

/** One passive account snapshot, never a sign-in or credential-validation request. */
suspend fun DshApiClient.accountGetState(): RpcResult<HostAccountView> =
    call("account/getState", JsonObject(emptyMap()), HostAccountView.serializer())

private fun AccountClientMetadata.asArgs(): JsonObject = buildJsonObject {
    put("client", buildJsonObject {
        put("version", JsonPrimitive(version))
        put("locale", JsonPrimitive(locale))
        put("timezoneOffsetSeconds", JsonPrimitive(timezoneOffsetSeconds))
    })
}

/** Null means the account grant disappeared or changed during the Host's profile query. */
suspend fun DshApiClient.accountGetProfile(client: AccountClientMetadata): RpcResult<HostAccountProfileResult?> =
    call("account/getProfile", client.asArgs(), HostAccountProfileResult.serializer().nullable)

/** Null means the account grant disappeared or changed during the Host's balance query. */
suspend fun DshApiClient.accountGetBalance(client: AccountClientMetadata): RpcResult<HostAccountBalanceResult?> =
    call("account/getBalance", client.asArgs(), HostAccountBalanceResult.serializer().nullable)
