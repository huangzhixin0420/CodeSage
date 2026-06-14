package com.codesage.acp.transport

/**
 * ACP 会话传输层抽象
 *
 * 一次 [AcpSessionTransport] 对应一条 ACP 连接（stdio、socket、内存 fake 等）。
 * 服务端通过 [readLine] 循环读取 JSON-RPC 请求，处理完成后通过 [writeLine] 返回响应。
 */
interface AcpSessionTransport {

    /**
     * 读取一行 JSON-RPC 消息。返回 null 表示对端已关闭。
     */
    suspend fun readLine(): String?

    /**
     * 写入一行 JSON-RPC 消息并刷新缓冲区。
     */
    suspend fun writeLine(message: String)

    /**
     * 关闭传输层，释放底层资源。
     */
    suspend fun close()

    /**
     * 当前传输是否仍处于打开状态。
     */
    val isOpen: Boolean
}
