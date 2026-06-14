package com.codesage.observability

import com.codesage.shared.config.AdvancedSection
import com.codesage.shared.config.SettingsFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * 6.13.2 OpenTelemetry 导出器测试
 */
class OpenTelemetryExporterTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `exports OTLP JSON when telemetry is enabled`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        val exporter = createExporter(enabled = true)
        val trace = buildSampleTrace()
        exporter.exportBlocking(server.url("/v1/traces").toString(), exporter.buildOtlpJson(trace))

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request, "Expected an OTLP export request")
        assertEquals("POST", request!!.method)
        assertEquals("/v1/traces", request.path)
        val contentType = request.getHeader("Content-Type") ?: ""
        assertTrue(contentType.startsWith("application/json"), "Expected JSON content type, got $contentType")

        val body = request.body.readUtf8()
        val json = Json.parseToJsonElement(body).jsonObject
        val resourceSpans = json["resourceSpans"]?.jsonArray
        assertFalse(resourceSpans.isNullOrEmpty(), "OTLP body should contain resourceSpans")

        val scopeSpans = resourceSpans!![0].jsonObject["scopeSpans"]?.jsonArray
        assertFalse(scopeSpans.isNullOrEmpty())

        val spans = scopeSpans!![0].jsonObject["spans"]?.jsonArray
        assertFalse(spans.isNullOrEmpty())
        assertEquals(2, spans!!.size)

        val spanNames = spans.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue("root" in spanNames)
        assertTrue("child" in spanNames)

        val rootSpan = spans.find { it.jsonObject["name"]?.jsonPrimitive?.content == "root" }!!.jsonObject
        assertTrue(rootSpan["traceId"]?.jsonPrimitive?.content?.length == 32)
        assertTrue(rootSpan["spanId"]?.jsonPrimitive?.content?.length == 16)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `onTraceEnded does nothing when telemetry is disabled`() = runBlocking {
        val exporter = createExporter(enabled = false)
        val tracer = ExecutionTracer()
        tracer.addListener(exporter)

        val ctx = tracer.startTrace("disabled_trace")
        ctx.end()

        delay(300)
        assertEquals(0, server.requestCount, "No export request should be sent when disabled")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `onTraceEnded swallows export failure and does not throw`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Collector Error"))

        val exporter = createExporter(enabled = true)
        val trace = buildSampleTrace()

        assertDoesNotThrow {
            exporter.onTraceEnded(trace)
        }

        delay(500)
        assertEquals(1, server.requestCount, "Failed export should still produce one request")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `ExecutionTracer notifies listener when trace ends`() = runBlocking {
        val exporter = createExporter(enabled = true)
        val tracer = ExecutionTracer()
        tracer.addListener(exporter)

        val ctx = tracer.startTrace("notified_trace")
        val child = ctx.childSpan("child")
        ctx.endChildSpan(child)
        ctx.end()

        delay(500)
        assertEquals(1, server.requestCount, "Listener should trigger one export")
    }

    private fun createExporter(enabled: Boolean): OpenTelemetryExporter {
        return OpenTelemetryExporter(
            settingsProvider = {
                SettingsFile(
                    advanced = AdvancedSection(
                        enableTelemetry = enabled,
                        telemetryEndpoint = server.url("/v1/traces").toString()
                    )
                )
            }
        )
    }

    private fun buildSampleTrace(): ExecutionTracer.Trace {
        val tracer = ExecutionTracer()
        val ctx = tracer.startTrace("root", sessionId = "session-1")
        val child = ctx.childSpan("child", mapOf("tool" to "test"))
        ctx.endChildSpan(child)
        ctx.end()
        return tracer.getTraceHistory(1).first()
    }
}
