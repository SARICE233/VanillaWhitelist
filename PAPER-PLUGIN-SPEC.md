# WTUMC Paper Plugin 开发文档

## 概述

本插件是一个 Paper 服务器插件，负责：
1. 作为 WebSocket **Server** 运行，接受网站的 WebSocket Client 连接
2. 向网站实时推送服务器状态、玩家事件、世界统计数据
3. 接收网站发来的白名单管理命令并执行

**通信方向**：网站主动连接插件（网站是 Client，插件是 Server）

## 环境要求

- Paper 1.20.4+（推荐 1.21+）
- Java 17+
- Kotlin 或 Java 均可（推荐 Kotlin + Coroutines）

## 项目结构建议

```
wtumc-plugin/
├── build.gradle.kts
├── src/main/
│   ├── kotlin/com/wtumc/plugin/
│   │   ├── WtumcPlugin.kt              # 主插件类
│   │   ├── config/
│   │   │   └── PluginConfig.kt          # config.yml 管理
│   │   ├── ws/
│   │   │   ├── WsServer.kt             # WebSocket Server
│   │   │   ├── WsSession.kt            # 单个连接会话
│   │   │   └── MessageHandler.kt       # 消息路由
│   │   ├── data/
│   │   │   ├── StatsCollector.kt       # 数据采集器
│   │   │   ├── PlayerTracker.kt        # 玩家事件追踪
│   │   │   └── WorldTracker.kt         # 世界统计追踪
│   │   └── command/
│   │       └── PluginCommands.kt        # 调试命令
│   └── resources/
│       ├── plugin.yml
│       └── config.yml
```

## config.yml 格式

```yaml
# WebSocket 服务器配置
websocket:
  # 监听端口
  port: 25585
  # 认证密钥（必须与网站 config 表中 plugin_ws_secret 一致）
  secret: "change-me-to-a-random-string"
  # 是否启用
  enabled: true
  # 绑定地址（0.0.0.0 = 所有网卡）
  host: "0.0.0.0"

# 数据采集配置
stats:
  # 服务器状态推送间隔（秒）
  push-interval-seconds: 30
  # 世界统计推送间隔（秒）
  world-stats-interval-seconds: 300
  # 玩家统计批量推送间隔（秒）
  player-stats-interval-seconds: 600

# 调试
debug: false
```

## plugin.yml

```yaml
name: WtumcPlugin
version: '1.0.0'
main: com.wtumc.plugin.WtumcPlugin
api-version: '1.20'
description: WTUMC Community Website Integration Plugin
authors:
  - WTUMC Team
commands:
  wtumc:
    description: WtumcPlugin admin commands
    permission: wtumc.admin
permissions:
  wtumc.admin:
    description: Admin access to plugin commands
    default: op
```

## WebSocket 协议

### 连接生命周期

```
网站启动 → 连接到 ws://server:25585
         → 收到连接后，等待网站发送 auth 消息
         → 验证 secret → 返回 auth_result
         → 开始双向通信
         → 网站断开 → 清理会话
```

**重要**：插件只允许**一个**活跃连接。新连接进来时，关闭旧连接。

### 消息格式

所有消息都是 JSON，包含 `type` 字段。请求/响应类消息额外包含 `id` 字段。

### 完整消息列表

#### 1. 认证

```json
// ← 网站发送
{
  "type": "auth",
  "id": "auth-1",
  "secret": "config中的plugin_ws_secret"
}

// → 插件响应（成功）
{
  "type": "auth_result",
  "id": "auth-1",
  "success": true
}

// → 插件响应（失败）
{
  "type": "auth_result",
  "id": "auth-1",
  "success": false,
  "error": "INVALID_SECRET"
}
```

**实现要点**：
- 收到连接后 10 秒内未收到 auth 消息 → 关闭连接
- auth 失败 → 关闭连接
- auth 成功前，忽略所有其他消息

#### 2. 心跳

```json
// ← 网站发送
{ "type": "ping" }

// → 插件响应
{ "type": "pong" }
```

**实现要点**：
- 网站每 15 秒发送一次 ping
- 如果 10 秒内没收到 pong，网站会断开
- 插件收到 ping 后应立即回复 pong

#### 3. 白名单操作

```json
// ← 网站发送：添加白名单
{
  "type": "whitelist_add",
  "id": "wl-1",
  "player_name": "Steve",
  "player_uuid": "可选的UUID"
}

// → 插件响应（成功）
{
  "type": "whitelist_result",
  "id": "wl-1",
  "action": "whitelist_add",
  "success": true,
  "player_name": "Steve"
}

// → 插件响应（失败）
{
  "type": "whitelist_result",
  "id": "wl-1",
  "action": "whitelist_add",
  "success": false,
  "player_name": "Steve",
  "error": "PLAYER_NOT_FOUND"
}
```

```json
// ← 网站发送：移除白名单
{
  "type": "whitelist_remove",
  "id": "wl-2",
  "player_name": "Steve"
}

// → 插件响应（同 whitelist_result 格式）
```

**实现要点**：
- 使用 Bukkit 的 `Whitelist` API 或直接操作 `whitelist.json`
- `whitelist_add`：调用 `server.getWhitelist().addPlayer(playerProfile)`
- `whitelist_remove`：调用 `server.getWhitelist().removePlayer(playerProfile)`
- 如果提供了 UUID，优先用 UUID 查找；否则用名字
- 错误码：
  - `PLAYER_NOT_FOUND` — 玩家从未加入过服务器
  - `ALREADY_WHITELISTED` — 已在白名单中
  - `WHITELIST_DISABLED` — 服务器未开启白名单模式
  - `INTERNAL_ERROR` — 内部错误

#### 4. 服务器状态推送

```json
// → 插件每 30 秒主动推送
{
  "type": "server_stats",
  "server_id": "main",
  "tps": 19.8,
  "mspt": 12.3,
  "memory_used": 2048,
  "memory_max": 4096,
  "loaded_chunks": 12500,
  "entity_count": 3420,
  "online_count": 15,
  "players": [
    {
      "name": "Steve",
      "uuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
      "dimension": "overworld",
      "x": 100.5,
      "y": 64.0,
      "z": -200.3
    }
  ]
}
```

**数据采集方法**：

```kotlin
// TPS — Paper API 直接提供
val tps = Bukkit.getTPS()  // double[]: [1m, 5m, 15m]

// MSPT — Paper API
val mspt = Bukkit.getAverageTickTime()  // double (ms)

// 内存
val runtime = Runtime.getRuntime()
val memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)  // MB
val memoryMax = runtime.maxMemory() / (1024 * 1024)  // MB

// 已加载区块数
val loadedChunks = Bukkit.getWorlds().sumOf { it.loadedChunkCount }

// 实体数
val entityCount = Bukkit.getWorlds().sumOf { it.entityCount }

// 在线玩家 + 维度
val players = Bukkit.getOnlinePlayers().map { player ->
    mapOf(
        "name" to player.name,
        "uuid" to player.uniqueId.toString(),
        "dimension" to player.world.environment.name.lowercase(),  // "normal" → "overworld"
        "x" to player.location.x,
        "y" to player.location.y,
        "z" to player.location.z
    )
}
```

**维度名称映射**：
- `NORMAL` → `"overworld"`
- `NETHER` → `"the_nether"`
- `THE_END` → `"the_end"`

#### 5. 玩家事件推送

```json
// → 玩家加入
{
  "type": "player_event",
  "event": "join",
  "player_name": "Steve",
  "player_uuid": "xxx"
}

// → 玩家离开
{
  "type": "player_event",
  "event": "leave",
  "player_name": "Steve",
  "player_uuid": "xxx",
  "playtime_seconds": 3600
}

// → 维度切换
{
  "type": "player_event",
  "event": "dimension_change",
  "player_name": "Steve",
  "player_uuid": "xxx",
  "from": "overworld",
  "to": "the_nether"
}

// → 玩家死亡
{
  "type": "player_event",
  "event": "death",
  "player_name": "Steve",
  "player_uuid": "xxx",
  "cause": "fall"
}
```

**需要监听的 Bukkit 事件**：

| Bukkit Event | 对应消息 | 注意事项 |
|---|---|---|
| `PlayerJoinEvent` | `join` | 用 `event.player.uniqueId` |
| `PlayerQuitEvent` | `leave` | 计算 `playtime_seconds`（记录 join 时间戳） |
| `PlayerChangedWorldEvent` | `dimension_change` | `from` = `event.from.environment`, `to` = `player.world.environment` |
| `PlayerDeathEvent` | `death` | `cause` 取 `event.damageSource.damageType.key` |

**实现要点**：
- 在内存中维护 `Map<UUID, Long>` 记录每个玩家的加入时间戳
- `PlayerJoinEvent` 时 put 当前时间
- `PlayerQuitEvent` 时计算差值作为 `playtime_seconds`，然后 remove

#### 6. 世界统计推送

```json
// → 插件每 5 分钟推送
{
  "type": "world_stats",
  "worlds": [
    {
      "name": "world",
      "type": "overworld",
      "explored_chunks": 12500,
      "total_blocks_placed": 1500000,
      "total_blocks_broken": 800000,
      "total_players_joined": 120
    }
  ]
}
```

**数据采集方法**：

```kotlin
// 已探索区块 — 没有直接 API，用已加载区块数近似
val exploredChunks = world.loadedChunkCount

// 方块统计 — 使用 BlockBreakEvent / BlockPlaceEvent 事件监听累加计数
// 注: Statistic.MINE_BLOCK 是材质限定的统计项（需要 Material 参数），
//     Statistic.USE_ITEM 追踪的是物品使用次数而非方块放置数，
//     因此不能直接使用 Statistic API 获取方块统计。
//     正确做法：监听 BlockBreakEvent 和 BlockPlaceEvent，
//     通过 SQLite 持久化累计计数。

// 总加入人次 — 维护一个计数器
// 在 PlayerJoinEvent 中递增并持久化到文件
```

**简化方案**：世界统计数据采集开销较大，建议：
1. 首次启动时全量扫描一次（异步）
2. 之后通过事件监听增量更新
3. 每 5 分钟推送一次缓存数据

#### 7. 玩家统计批量推送

```json
// → 插件在玩家离开时或每 10 分钟批量推送
{
  "type": "player_stats_batch",
  "players": [
    {
      "uuid": "xxx",
      "name": "Steve",
      "playtime_seconds": 72000,
      "deaths": 15,
      "kills": 230,
      "blocks_placed": 50000,
      "blocks_broken": 30000,
      "distance_walked": 125000.5,
      "achievements_count": 25,
      "first_join": "2026-01-15T10:00:00Z",
      "last_join": "2026-07-06T14:30:00Z"
    }
  ]
}
```

**数据采集方法**：

```kotlin
fun collectPlayerStats(player: OfflinePlayer, worldTracker: WorldTracker): Map<String, Any> {
    val uuid = player.uniqueId.toString()
    return mapOf(
        "uuid" to uuid,
        "name" to (player.name ?: "Unknown"),
        "playtime_seconds" to worldTracker.getPlayerPlaytimeSeconds(uuid), // join/quit 事件累加
        "deaths" to player.getStatistic(Statistic.DEATHS),
        "kills" to player.getStatistic(Statistic.MOB_KILLS),
        "blocks_placed" to worldTracker.getPlayerBlocksPlaced(uuid), // BlockPlaceEvent 累加
        "blocks_broken" to worldTracker.getPlayerBlocksBroken(uuid), // BlockBreakEvent 累加
        "distance_walked" to player.getStatistic(Statistic.WALK_ONE_CM) / 100.0,
        "achievements_count" to worldTracker.getPlayerAdvancementsCount(uuid), // PlayerAdvancementDoneEvent 累加
        "first_join" to player.firstPlayed.let { Instant.ofEpochMilli(it).toString() },
        "last_join" to player.lastPlayed.let { Instant.ofEpochMilli(it).toString() }
    )
}
```
**注**: 以下几项不能直接使用 Statistic API，需通过事件监听累加：
- `playtime_seconds`: `Statistic.PLAY_ONE_MINUTE` 在 1.21 中不可靠 → 通过 `PlayerJoinEvent`/`PlayerQuitEvent` 计算会话时长并累加入 DB
- `blocks_placed`/`blocks_broken`: `Statistic.MINE_BLOCK` 是材质限定的（需 Material 参数），`Statistic.USE_ITEM` 追踪的是物品使用 → 通过 `BlockBreakEvent`/`BlockPlaceEvent` 累加
- `achievements_count`: 旧的 `Achievement` 枚举在 1.12+ 已废弃 → 通过 `PlayerAdvancementDoneEvent` 累加（过滤掉配方解锁）

### 错误码一览

| Code | 含义 | 触发场景 |
|---|---|---|
| `INVALID_SECRET` | 认证密钥错误 | auth 消息的 secret 不匹配 |
| `PLAYER_NOT_FOUND` | 玩家不存在 | whitelist_add 时找不到玩家 |
| `ALREADY_WHITELISTED` | 已在白名单中 | 重复添加 |
| `WHITELIST_DISABLED` | 服务器白名单未启用 | server.properties 中 white-list=false |
| `INTERNAL_ERROR` | 内部错误 | 未预期异常 |

## 调试命令

```
/wtumc status          — 显示 WebSocket 连接状态
/wtumc stats           — 立即推送一次服务器状态
/wtumc whitelist add <player>  — 手动添加白名单
/wtumc whitelist remove <player> — 手动移除白名单
/wtumc reload          — 重载 config.yml
```

## 安全注意事项

1. **密钥保护**：`config.yml` 中的 `secret` 不要提交到公开仓库
2. **端口安全**：WebSocket 端口应仅在内网暴露，或通过防火墙限制访问来源
3. **单连接限制**：只允许一个活跃的 WebSocket 连接，防止多网站实例冲突
4. **输入校验**：对收到的 `player_name` 做合法性校验（3-16 字符，仅字母数字下划线）

## 测试清单

- [ ] 插件加载后自动启动 WebSocket Server
- [ ] 网站可以连接并认证成功
- [ ] 认证失败时连接被关闭
- [ ] 心跳 ping/pong 正常工作
- [ ] 服务器状态每 30 秒推送一次
- [ ] 玩家加入/离开事件正确推送
- [ ] 维度切换事件正确推送
- [ ] 玩家死亡事件正确推送
- [ ] 白名单添加命令执行成功
- [ ] 白名单移除命令执行成功
- [ ] 世界统计数据正确采集和推送
- [ ] 玩家统计数据正确采集和推送
- [ ] 插件重载后 WebSocket Server 重启
- [ ] 网站断开后插件继续正常运行
- [ ] config.yml 热重载正常工作

## 推荐依赖

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")  // WebSocket Server
    implementation("com.google.code.gson:gson:2.11.0")         // JSON (Paper 自带)
}
```

推荐使用 `org.java-websocket` 库实现 WebSocket Server，简单可靠。

## 发布

编译为 JAR 文件后放入服务器 `plugins/` 目录，重启服务器即可。首次启动会生成 `plugins/WtumcPlugin/config.yml`，填写配置后执行 `/wtumc reload` 或重启服务器。
