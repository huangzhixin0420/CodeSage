package com.codesage.observability

import com.codesage.shared.config.SettingsFile
import com.codesage.shared.net.ProxyAwareHttpClientFactory
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 6.13.2 OpenTelemetry 追踪导出器
 *
 * 将 [ExecutionTracer] 生成的 trace/span 树以 OTLP/JSON 格式导出到配置的 Collector。
 *
 * - 读取 `settings.json` 的 `advanced.enableTelemetry` / `telemetryEndpoint`。
 * - 默认 endpoint：`http://localhost:4318/v1/traces`。
 * - 通过 [ProxyAwareHttpClientFactory] 复用代理配置。
 * - 导出为异步协程，失败时仅打 warn 日志，不中断业务。
 *
 * @param settingsProvider 获取当前设置；生产环境使用 [com.codesage.shared.config.SettingsRepository]，
 *                         测试环境可注入固定配置。
 * @param client OkHttp client；默认使用代理感知工厂。
 * @param serviceName OTLP resource属性 service.name。
 * @param serviceVersion OTLP资源属性 service.version。
 */
class OpenTelemetryExporter(
    private val settingsProvider: () -> SettingsFile,
    private val client: OkHttpClient = ProxyAwareHttpClientFactory.build(),
    private val serviceName: String = "CodeSage",
    private val serviceVersion: String = "1.0.0"
) : ExecutionTracer.TraceListener {

    private val logger = Logger.getLogger<OpenTelemetryExporter>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { prettyPrint = false }

    /**
     * OTLP 默认接收端点。
     */
    companion object {
        const val DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces"
    }

    override fun onTraceEnded(trace: ExecutionTracer.Trace) {
        val settings = runCatching { settingsProvider() }.getOrNull() ?: return
        if (!settings.advanced.enableTelemetry) return

        val endpoint = settings.advanced.telemetryEndpoint.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
        val payload = runCatching { buildOtlpJson(trace) }.getOrElse { e ->
            logger.warn("Failed to build OTLP payload for trace ${trace.id}", e)
            return
        }

        scope.launch {
            try {
                export(endpoint, payload)
            } catch (e: Exception) {
                logger.warn("OpenTelemetry export failed for trace ${trace.id}: ${e.message}")
            }
        }
    }

    /**
     * 关闭导出器，取消待处理任务。
     */
    fun shutdown() {
        scope.cancel()
    }

    /**
     * 同步导出（供测试使用）。
     */
    internal fun exportBlocking(endpoint: String, payload: String) {
        runBlocking { export(endpoint, payload) }
    }

    private suspend fun export(endpoint: String, payload: String) {
        withContext(Dispatchers.IO) {
            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                    }
                }
        }
    }

    /**
     * 将 trace 转换为 OTLP/JSON 字符串。
     */
    internal fun buildOtlpJson(trace: ExecutionTracer.Trace): String {
        val allSpans = listOf(trace.rootSpan) + trace.spans
        val traceIdHex = toTraceId(trace.id)

        val spansArray = JsonArray(
            allSpans.map { span ->
                buildSpanObject(span, traceIdHex)
            }
        )

        val otlp = JsonObject(
            mapOf(
                "resourceSpans" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "resource" to JsonObject(
                                    mapOf(
                                        "attributes" to JsonArray(
                                            listOf(
                                                attribute("service.name", serviceName),
                                                attribute("service.version", serviceVersion),
                                                attribute("session.id", trace.sessionId ?: "")
                                            )
                                        )
                                    )
                                ),
                                "scopeSpans" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "scope" to JsonObject(
                                                    mapOf(
                                                        "name" to JsonPrimitive("codesage"),
                                                        "version" to JsonPrimitive(serviceVersion)
                                                    )
                                                ),
                                                "spans" to spansArray
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        return json.encodeToString(JsonObject.serializer(), otlp)
    }

    private fun buildSpanObject(span: ExecutionTracer.Span, traceIdHex: String): JsonObject {
        val fields = mutableMapOf<String, JsonElement>()
        fields["traceId"] = JsonPrimitive(traceIdHex)
        fields["spanId"] = JsonPrimitive(toSpanId(span.id))
        span.parentSpanId?.let {
            fields["parentSpanId"] = JsonPrimitive(toSpanId(it))
        }
        fields["name"] = JsonPrimitive(span.name)
        fields["kind"] = JsonPrimitive(1) // SPAN_KIND_INTERNAL
        fields["startTimeUnixNano"] = JsonPrimitive(msToNano(span.startTime))
        fields["endTimeUnixNano"] = JsonPrimitive(msToNano(span.endTime ?: span.startTime))
        fields["status"] = JsonObject(
            mapOf(
                "code" to JsonPrimitive(toStatusCode(span.status))
            )
        )

        if (span.attributes.isNotEmpty()) {
            fields["attributes"] = JsonArray(span.attributes.map { (k, v) -> attribute(k, v) })
        }

        if (span.events.isNotEmpty()) {
            fields["events"] = JsonArray(
                span.events.map { event ->
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(event.name),
                            "timeUnixNano" to JsonPrimitive(msToNano(event.timestamp)),
                            "attributes" to JsonArray(
                                event.attributes.map { (k, v) -> attribute(k, v) }
                            )
                        )
                    )
                }
            )
        }

        return JsonObject(fields)
    }

    private fun attribute(key: String, value: String): JsonObject {
        return JsonObject(
            mapOf(
                "key" to JsonPrimitive(key),
                "value" to JsonObject(mapOf("stringValue" to JsonPrimitive(value)))
            )
        )
    }

    private fun toTraceId(raw: String): String {
        return raw.removePrefix("trace_")
            .replace("-", "")
            .lowercase()
            .take(32)
            .padEnd(32, '0')
    }

    private fun toSpanId(raw: String): String {
        return raw.removePrefix("span_")
            .removePrefix("trace_")
            .replace("-", "")
            .lowercase()
            .take(16)
            .padEnd(16, '0')
    }

    private fun msToNano(ms: Long): String = (ms * 1_000_000).toString()

    private fun toStatusCode(status: ExecutionTracer.TraceStatus): Int = when (status) {
        ExecutionTracer.TraceStatus.OK -> 1 // STATUS_CODE_OK
        ExecutionTracer.TraceStatus.ERROR -> 2 // STATUS_CODE_ERROR
        ExecutionTracer.TraceStatus.CANCELLED -> 2 // treat cancelled as error
    }
}
