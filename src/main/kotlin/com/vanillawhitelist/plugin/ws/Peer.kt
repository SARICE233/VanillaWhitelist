package com.vanillawhitelist.plugin.ws

/**
 * 「对端」抽象：能被回消息的一方。
 * - 入站模式下是 [WsSession]（网站连过来）
 * - 出站模式下是 [WsClient] 自己（本端连过去）
 * 消息处理层只依赖这个接口，两种模式共用同一套协议实现。
 */
interface Peer {
    fun send(json: String)
    var authenticated: Boolean
    fun close()
}
