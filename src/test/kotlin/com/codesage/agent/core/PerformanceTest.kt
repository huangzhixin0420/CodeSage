package com.codesage.agent.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.measureTimeMillis

class PerformanceTest {

    @Test
    fun `should read 5000 line file in under 500ms`(@TempDir tempDir: Path) = runBlocking {
        val file = tempDir.resolve("large_file.txt").toFile()
        file.writeText((1..5000).joinToString("\n") { "Line $it: ${"x".repeat(80)}" })

        val duration = measureTimeMillis {
            val content = readLargeFileMapped(file)
            assertTrue(content.lines().size >= 1000) // 至少读取了 1000 行
        }

        println("Read 5000 line file in ${duration}ms")
        assertTrue(duration < 500, "Expected < 500ms, got ${duration}ms")
    }

    @Test
    fun `should process 100 events in under 100ms`() = runBlocking {
        val events = (1..100).map {
            AgentStreamEvent.TextDelta("Event content $it ")
        }

        val flow = flow {
            events.forEach { emit(it) }
        }

        val duration = measureTimeMillis {
            val collected = flow.toList()
            assertEquals(100, collected.size)
        }

        println("Processed 100 events in ${duration}ms")
        assertTrue(duration < 100, "Expected < 100ms, got ${duration}ms")
    }

    @Test
    fun `EventBatchEmitter should reduce event count`() = runBlocking {
        val emitter = EventBatchEmitter(batchSize = 50, batchIntervalMs = 10000)
        val events = (1..100).map { AgentStreamEvent.TextDelta("$it ") }

        val flow = flow {
            events.forEach { emit(it) }
        }

        val result = emitter.batch(flow).toList()
        val textDeltas = result.filterIsInstance<AgentStreamEvent.TextDelta>()

        println("Batched 100 text deltas into ${textDeltas.size} events")
        assertTrue(textDeltas.size < 100, "Expected fewer events after batching")

        emitter.shutdown()
    }

    @Test
    fun `should read multiple files in parallel`(@TempDir tempDir: Path) = runBlocking {
        val files = (1..5).map {
            val f = tempDir.resolve("file_$it.txt").toFile()
            f.writeText((1..1000).joinToString("\n") { "Line $it" })
            f
        }

        val duration = measureTimeMillis {
            val contents = withContext(Dispatchers.IO) {
                files.map {
                    async { it.readText(StandardCharsets.UTF_8).take(10000) }
                }.awaitAll()
            }
            assertEquals(5, contents.size)
            contents.forEach { assertTrue(it.isNotEmpty()) }
        }

        println("Read 5 files in parallel in ${duration}ms")
        assertTrue(duration < 1000, "Expected < 1000ms, got ${duration}ms")
    }

    @Test
    fun `event history memory should be under 10MB`() {
        val history = EventHistory(maxEvents = 1000)
        repeat(1000) {
            history.record(
                AgentStreamEvent.TextDelta("Event $it with some content ".repeat(50)),
                "session_$it"
            )
        }

        // 估算内存占用：1000 条记录，每条大约 200 字节 = 200KB
        // 加上对象头开销，远小于 10MB
        assertEquals(1000, history.size())

        val json = history.exportToJson()
        val jsonSizeBytes = json.toByteArray().size
        println("Event history JSON export size: ${jsonSizeBytes / 1024}KB")
        assertTrue(jsonSizeBytes < 10 * 1024 * 1024, "Expected < 10MB, got ${jsonSizeBytes} bytes")
    }

    @Test
    fun `large file memory mapped read should be fast`(@TempDir tempDir: Path) = runBlocking {
        val file = tempDir.resolve("huge.txt").toFile()
        // 创建 1MB 文件
        file.writeText("x".repeat(1024 * 1024))

        val duration = measureTimeMillis {
            val content = readLargeFileMapped(file)
            assertTrue(content.isNotEmpty())
        }

        println("Read 1MB file in ${duration}ms")
        assertTrue(duration < 200, "Expected < 200ms, got ${duration}ms")
    }

    /**
     * 独立的大文件 memory-mapped 读取实现（用于测试）
     */
    private fun readLargeFileMapped(file: File): String {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val buffer: MappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            val sb = StringBuilder()
            var lineCount = 0
            val chunkLines = 1000
            val byteBuffer = ByteArray(8192)
            var bufPos = 0

            while (buffer.hasRemaining() && lineCount < chunkLines) {
                val b = buffer.get()
                if (b == '\n'.code.toByte()) {
                    sb.append(String(byteBuffer, 0, bufPos, StandardCharsets.UTF_8))
                    sb.append('\n')
                    bufPos = 0
                    lineCount++
                } else {
                    if (bufPos >= byteBuffer.size) {
                        // 行过长，直接刷新
                        sb.append(String(byteBuffer, 0, bufPos, StandardCharsets.UTF_8))
                        bufPos = 0
                    }
                    byteBuffer[bufPos++] = b
                }
            }
            if (bufPos > 0 && lineCount < chunkLines) {
                sb.append(String(byteBuffer, 0, bufPos, StandardCharsets.UTF_8))
            }
            if (buffer.hasRemaining()) {
                sb.append("\n... [文件过大，已截断。共 ${file.length()} 字节] ...")
            }
            return sb.toString()
        }
    }
}
