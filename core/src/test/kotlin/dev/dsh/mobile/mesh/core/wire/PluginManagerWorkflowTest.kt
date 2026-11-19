package dev.dsh.mobile.mesh.core.wire

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlinx.coroutines.test.runTest
import dev.dsh.mobile.mesh.core.wire.dto.SettingsPathOpView
import dev.dsh.mobile.mesh.core.wire.dto.PluginManagerInstallOptions
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same listing and enablement round trip Android performs through direct, SSH, or Mesh RPC transport. */
class PluginManagerWorkflowTest {
    private class ManagedHost : RpcTransport {
        var enabled = false
        var settingsRevision = 2L
        var setting = "original"
        var scriptApproved = false
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val envelope = WireJson.parseToJsonElement(body).jsonObject
            val rpcId = envelope.getValue("rpcId").jsonPrimitive.content
            val payload = envelope.getValue("payload").jsonObject.getValue("args").jsonObject
            val value = when (path) {
                "/api/pluginManager/listBundles" ->
                    """[{"name":"example","official":false,"availability":"profile","enabled":$enabled,"installed":true,"rows":[]}]"""
                "/api/pluginManager/listPlugins" -> "[]"
                "/api/pluginManager/inspect" -> """{"status":"accepted","kind":"registry","name":"example","version":"1.0.0","bundle":true,"registry":null}"""
                "/api/pluginManager/installBundle" -> {
                    val options = payload.getValue("options").jsonObject
                    scriptApproved = options["approvedBuilds"]?.jsonArray?.any {
                        it.jsonPrimitive.content == "example@1.0.0"
                    } == true
                    if (scriptApproved) """{"changed":true,"application":"applied","stage":"install","target":"example","bundle":"example"}"""
                    else """{"changed":false,"application":"failed","stage":"install","target":"example","pendingBuilds":["example@1.0.0"],"error":{"code":"operation-error"}}"""
                }
                "/api/settings/describe" -> """{"writable":true,"hasDocument":true,"namespaces":[${settingsView()}]}"""
                "/api/settings/mutate" -> {
                    if (payload.getValue("expectedRevision").jsonPrimitive.content.toLong() != settingsRevision) {
                        return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$rpcId","result":{"ok":false,"error":{"code":"settings/conflict","message":"stale"}}}""")
                    }
                    val op = payload.getValue("ops").jsonArray.single().jsonObject
                    assertEquals("set", op.getValue("op").jsonPrimitive.content)
                    assertEquals("label", op.getValue("path").jsonArray.single().jsonPrimitive.content)
                    setting = op.getValue("value").jsonPrimitive.content
                    settingsRevision++
                    settingsView()
                }
                "/api/pluginManager/setBundleEnabled" -> {
                    assertEquals("example", payload.getValue("name").jsonPrimitive.content)
                    enabled = payload.getValue("enabled").jsonPrimitive.content.toBooleanStrict()
                    """{"changed":true,"application":"restart-required","restartReason":"no-hmr","stage":"enable","target":"example","enabled":$enabled}"""
                }
                else -> error("Unexpected endpoint $path")
            }
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":"$rpcId","result":{"ok":true,"value":$value}}""")
        }

        private fun settingsView() = """{"ns":"example-row","autoGenerate":true,"schema":{"uid":2,"refs":{"2":{"type":"object","dict":{"label":1}},"1":{"type":"string"}}},"value":{"label":"$setting"},"applies":"live","revision":$settingsRevision}"""

        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T = error("unused")
        override suspend fun upload(
            path: String, contentType: String, contentLength: Long, body: InputStream,
            onProgress: ((Long) -> Unit)?,
        ): RpcHttpResponse = error("unused")
    }

    @Test fun `enabling a managed bundle returns the host's restart outcome and refreshed state`() = runTest {
        val host = ManagedHost()
        val client = DshApiClient(host)
        val before = client.listPluginManagerBundles() as RpcResult.Ok
        assertEquals(false, before.value.single().enabled)
        val result = client.setPluginManagerBundleEnabled(before.value.single().name, true) as RpcResult.Ok
        assertTrue(result.value.changed)
        assertEquals("restart-required", result.value.application)
        assertEquals("no-hmr", result.value.restartReason)
        val after = client.listPluginManagerBundles() as RpcResult.Ok
        assertTrue(after.value.single().enabled)
    }

    @Test fun `configuration edit uses the current host revision and refuses a stale write`() = runTest {
        val host = ManagedHost()
        val client = DshApiClient(host)
        val initial = (client.settingsDescribe() as RpcResult.Ok).value.namespaces.single()
        val updated = client.settingsMutate(
            initial.ns, listOf(SettingsPathOpView.Set(listOf("label"), JsonPrimitive("updated"))),
            initial.revision,
        ) as RpcResult.Ok
        assertEquals("updated", updated.value.value.jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals(initial.revision + 1, updated.value.revision)
        val stale = client.settingsMutate(
            initial.ns, listOf(SettingsPathOpView.Set(listOf("label"), JsonPrimitive("overwritten"))),
            initial.revision,
        ) as RpcResult.Err
        assertEquals("settings/conflict", stale.error.code)
        assertEquals("updated", host.setting)
    }

    @Test fun `install scripts remain blocked until an exact pending package is explicitly approved`() = runTest {
        val host = ManagedHost()
        val client = DshApiClient(host)
        val inspection = client.inspectPluginManagerBundle("example@1.0.0") as RpcResult.Ok
        assertEquals("accepted", inspection.value.status)
        val first = client.installPluginManagerBundle("example@1.0.0", PluginManagerInstallOptions(
            requestId = "first",
        )) as RpcResult.Ok
        assertEquals("failed", first.value.application)
        assertEquals(listOf("example@1.0.0"), first.value.pendingBuilds)
        assertEquals(false, host.scriptApproved)
        val approved = client.installPluginManagerBundle("example@1.0.0", PluginManagerInstallOptions(
            requestId = "approved", approvedBuilds = first.value.pendingBuilds,
        )) as RpcResult.Ok
        assertEquals("applied", approved.value.application)
        assertTrue(host.scriptApproved)
    }

    @Test fun `a relay closing during an installation reply surfaces a recoverable transport error`() = runBlocking {
        val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val brokenBody = object : ResponseBody() {
                private val stream = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("relay closed")
                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = -1L
                override fun source() = stream
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(brokenBody).build()
        }.build()
        val client = DshApiClient(OkHttpRpcTransport("http://harness.example", okHttp))
        val result = withTimeout(5_000L) {
            client.installPluginManagerBundle("example", PluginManagerInstallOptions(requestId = "in-flight"))
        } as RpcResult.Err
        assertEquals("internal", result.error.code)
        assertTrue(result.error.message.contains("relay closed"))
    }
}
