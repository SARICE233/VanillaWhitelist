package com.vanillawhitelist.plugin.ws

/**
 * 传输层抽象。两种模式共用同一套消息协议，只有「谁发起连接」不同：
 * - [WsServer] 入站模式（mode=server）：网站主动连过来，需要开放端口
 * - [WsClient] 出站模式（mode=client）：本端主动连网站，不需要开放任何端口
 */
interface Transport {

    /** 是否已在运行 */
    val isRunning: Boolean

    /** 启动传输层 */
    fun start()

    /** 停止并释放资源 */
    fun stop()

    /** 重启（用于 /vwl reload） */
    fun restart()

    /** 推送消息；对端不在线时写入本地队列，等重连补发 */
    fun send(json: String)

    /** 是否已有「已认证」的对端 */
    fun hasConnections(): Boolean

    /** 待发队列长度 */
    fun bufferSize(): Int

    /** 补发离线期间缓冲的消息 */
    fun flushBuffer()
}
