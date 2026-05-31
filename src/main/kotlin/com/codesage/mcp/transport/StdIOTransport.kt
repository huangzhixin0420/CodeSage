package com.codesage.mcp.client

import com.codesage.mcp.transport.*
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * MCP传输接口
 */
interface MCPTransport {
    suspend fun connect(): Boolean
    suspend fun disconnect()
    suspend fun send(message: String): String?
    fun isConnected(): Boolean

    /**
     * 发送通知（无需等待响应）
     * 默认实现调用 send，子类可覆盖以优化性能
     */
    suspend fun sendNotification(message: String) {
        send(message)
    }
}

/**
 * StdIO传输实现
 */
class StdIOTransport(
    private val config: MCPServerConfig
) : MCPTransport {
    private val logger = Logger.getLogger<StdIOTransport>()

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            val transport = config.transportType as? TransportType.StdIO
                ?: throw IllegalArgumentException("Not a StdIO transport")

            val builder = ProcessBuilder(transport.command, *transport.args.toTypedArray())
            builder.redirectErrorStream(true)

            process = builder.start()
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            writer = OutputStreamWriter(process!!.outputStream)

            logger.info("StdIO transport connected: ${transport.command}")
            true
        } catch (e: Exception) {
            logger.error("Failed to connect StdIO transport", e)
            false
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            writer?.close()
            reader?.close()
            process?.destroy()
            // Give process a moment to exit gracefully, then force kill if needed
            if (process?.waitFor(2, TimeUnit.SECONDS) == false) {
                process?.destroyForcibly()
            }
            process = null
            logger.info("StdIO transport disconnected")
        } catch (e: Exception) {
            logger.error("Error disconnecting", e)
        }
    }

    override suspend fun send(message: String): String? = withContext(Dispatchers.IO) {
        try {
            writer?.write(message)
            writer?.write("\n")
            writer?.flush()

            reader?.readLine()
        } catch (e: Exception) {
            logger.error("Failed to send message", e)
            null
        }
    }

    override suspend fun sendNotification(message: String): Unit = withContext(Dispatchers.IO) {
        try {
            writer?.write(message)
            writer?.write("\n")
            writer?.flush()
        } catch (e: Exception) {
            logger.error("Failed to send notification", e)
        }
    }

    override fun isConnected(): Boolean = process?.isAlive == true
}

/**
 * HTTP传输实现
 */
class HTTPTransport(
    private val config: MCPServerConfig
) : MCPTransport {
    private val logger = Logger.getLogger<HTTPTransport>()

    private val httpClient = okhttp3.OkHttpClient()
    private var connected = false

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            connected = true
            logger.info("HTTP transport connected: ${config.transportType}")
            true
        } catch (e: Exception) {
            logger.error("Failed to connect HTTP transport", e)
            false
        }
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun send(message: String): String? = withContext(Dispatchers.IO) {
        try {
            val transport = config.transportType as? TransportType.HTTP
                ?: return@withContext null

            val body = okhttp3.RequestBody.create(
                "application/json".toMediaType(),
                message
            )

            val requestBuilder = okhttp3.Request.Builder()
                .url(transport.url)
                .post(body)

            transport.headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.body?.string()
        } catch (e: Exception) {
            logger.error("Failed to send HTTP message", e)
            null
        }
    }

    override fun isConnected(): Boolean = connected
}
