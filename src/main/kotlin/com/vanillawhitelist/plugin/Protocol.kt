package com.vanillawhitelist.plugin

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * 通信协议版本。
 *
 * 三端（Paper 插件 / Fabric 模组 / NeoForge 模组）共用同一份契约，
 * 定义见仓库根目录的 PROTOCOL.md。不兼容改动时必须递增此值。
 */
const val PROTOCOL_VERSION = 1

/** 实现标识，用于 auth_result 的 impl 字段 */
const val IMPL = "paper"

private val stampGson = Gson()

/**
 * 给推送消息统一盖上 protocol_version。
 * 放在推送的唯一出口上，将来新增消息类型时不可能漏掉。
 */
fun stampProtocolVersion(json: String): String = try {
    val obj = JsonParser.parseString(json).asJsonObject
    obj.addProperty("protocol_version", PROTOCOL_VERSION)
    stampGson.toJson(obj)
} catch (e: Exception) {
    json
}
