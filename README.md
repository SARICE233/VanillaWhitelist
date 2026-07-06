# VanillaWhitelist

[![Status: Alpha](https://img.shields.io/badge/status-alpha-orange)](https://github.com/SARICE233/VanillaWhitelist)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.x-brightgreen)](https://papermc.io)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

> ⚠️ **开发中 (Alpha)** — 功能基本可用，但可能存在 Bug 和 API 变动。欢迎测试反馈。

VanillaWhitelist 是一个 **Paper 1.21+** 服务器插件，通过 WebSocket 将服务器状态实时推送到你的网站，同时支持网站端远程管理白名单。

## 功能

| 功能 | 说明 |
|---|---|
| 📡 WebSocket Server | 插件作为 WS 服务端，网站主动连接 |
| 🔐 密钥认证 | 连接需验证 `secret`，10 秒内未认证自动断开 |
| 📊 服务器状态推送 | TPS / MSPT / 内存 / 在线玩家（默认 30s 间隔） |
| 🎮 玩家事件实时推送 | 加入 / 离开 / 死亡 / 换维度 |
| 🗺️ 世界统计推送 | 加载区块数、累计加入人次（默认 5min 间隔） |
| 📈 玩家统计批量推送 | 游戏时长、杀敌、死亡、行走距离、方块破坏/放置（默认 10min 间隔） |
| 🔨 远程白名单管理 | 网站可添加/移除白名单玩家 |
| 💾 SQLite 持久化 | totalJoins 跨重启保留，网站离线时消息自动缓冲，重连补发 |
| ⌨️ 管理命令 | `/vwl status` `/vwl stats` `/vwl reload` `/vwl whitelist add/remove` |

## 快速开始

### 1. 安装

从 [Releases](https://github.com/SARICE233/VanillaWhitelist/releases) 下载最新 JAR，放入服务器 `plugins/` 目录，重启服务器。

### 2. 配置

首次启动后，编辑 `plugins/VanillaWhitelist/config.yml`：

```yaml
websocket:
  port: 25585                              # WebSocket 监听端口
  secret: "your-random-secret-string"      # 认证密钥（与网站端保持一致）
  host: "0.0.0.0"

stats:
  push-interval-seconds: 30                # 服务器状态推送间隔
  world-stats-interval-seconds: 300        # 世界统计推送间隔
  player-stats-interval-seconds: 600       # 玩家统计推送间隔

debug: false
```

修改后执行 `/vwl reload` 或重启服务器。

### 3. 网站端连接

网站作为 WebSocket **Client** 连接 `ws://your-server:25585`，连接后立即发送认证消息：

```json
{
  "type": "auth",
  "id": "auth-1",
  "secret": "your-random-secret-string"
}
```

认证成功后即可接收推送数据。详见 [PAPER-PLUGIN-SPEC.md](./PAPER-PLUGIN-SPEC.md)。

### 4. 测试

打开 `test-ws.html`（仓库附带），填写服务器地址和密钥，点击连接即可实时查看推送数据。

## 开发命令

| 命令 | 说明 |
|---|---|
| `/vwl status` | 查看 WebSocket 状态、连接数、缓冲队列 |
| `/vwl stats` | 立即推送一次服务器状态 |
| `/vwl whitelist add <player>` | 手动添加白名单 |
| `/vwl whitelist remove <player>` | 手动移除白名单 |
| `/vwl reload` | 重载配置并重启 WebSocket 服务 |

## 构建

```bash
# 需要 JDK 21+
./gradlew shadowJar
```

产物在 `build/libs/VanillaWhitelist-1.0.0.jar`。

## 技术栈

- Kotlin 2.0 / Gradle 9
- Paper API 1.21
- Java-WebSocket
- SQLite (JDBC)

## 协议

详细的 WebSocket 消息格式和 API 文档见 [PAPER-PLUGIN-SPEC.md](./PAPER-PLUGIN-SPEC.md)。

## License

MIT
