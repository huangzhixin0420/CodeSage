package com.codesage.acp.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内存中的 ACP 会话传输层，用于单元测试
 *
 * 构造时传入 [incoming]（读取对端写入的消息）和 [outgoing]（写入后由对端读取）。
 * 典型用法：
 * ```
 * val serverIncoming = Channel<String>(Channel.BUFFERED)
 * val serverOutgoing = Channel<String>(Channel.BUFFERED)
 * val client = InMemoryAcpSessionTransport(serverOutgoing, serverIncoming)
 * val server = InMemoryAcpSessionTransport(serverIncoming, serverOutgoing)
 * ```
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryAcpSessionTransport(
    private val incoming: ReceiveChannel<String>,
    private val outgoing: SendChannel<String>
) : AcpSessionTransport {

    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean
        get() = !closed.get() && !incoming.isClosedForReceive && !outgoing.isClosedForSend

    override suspend fun readLine(): String? {
        return try {
            incoming.receive()
        } catch (e: Exception) {
            closed.set(true)
            null
        }
    }

    override suspend fun writeLine(message: String) {
        try {
            outgoing.send(message)
        } catch (e: Exception) {
            closed.set(true)
        }
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                outgoing.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}

/**
 * 构造一对互相连通的内存传输层
 */
fun createInMemoryAcpTransportPair(): Pair<AcpSessionTransport, AcpSessionTransport> {
    val clientToServer = Channel<String>(Channel.BUFFERED)
    val serverToClient = Channel<String>(Channel.BUFFERED)
    val client = InMemoryAcpSessionTransport(serverToClient, clientToServer)
    val server = InMemoryAcpSessionTransport(clientToServer, serverToClient)
    return client to server
}
