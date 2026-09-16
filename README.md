# VanillaWhitelist

[![Status: Alpha](https://img.shields.io/badge/status-alpha-orange)](https://github.com/SARICE233/VanillaWhitelist)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.11-brightgreen)](https://papermc.io)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

> ⚠️ **开发中 (Alpha)** — 功能基本可用，但可能存在 Bug 与协议变动。

把服务器状态实时推送到你自己的网站，并支持从网站远程管理白名单。

## 关于三个版本

本项目有三个**相互独立**的实现，功能集与通信协议完全一致，但不是同一个 jar：

| 版本 | 运行平台 | 目标版本 | 所需 Java |
| --- | --- | --- | --- |
| **Paper 插件**（本仓库） | Paper 服务端 | Paper 1.21.11 | 21 |
| Fabric 模组 | Fabric 服务端 | MC 26.2 | 25 |
| NeoForge 模组 | NeoForge 服务端 | MC 26.2 | 25 |

三者同源同名、协议互通。**网站端只需实现一次协议，三种平台都能接。**

完整协议定义见 [`PROTOCOL.md`](./PROTOCOL.md)。

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 📡 WebSocket 服务端 | 本插件作为 WS 服务端，网站主动连接 |
| 🔐 密钥认证 | 连接需验证 `secret`；密钥不合格时拒绝启动 |
| 📊 服务器状态推送 | TPS / MSPT / 内存 / 区块数 / 实体数 / 在线玩家坐标与维度 / 运行时长（默认 30s） |
| 🎮 玩家事件实时推送 | 加入 / 离开（含本次时长）/ 死亡（含死因）/ 换维度 / 达成成就 |
| 🗺️ 世界统计推送 | **按世界分别统计**：区块数、放置与破坏方块数、加入人次、成就数（默认 5min） |
| 📈 玩家统计批量推送 | 在线时长、死亡、击杀、放置/破坏、行走距离、成就数、首次与最近加入（默认 10min） |
| 🏆 成就明细同步 | 每位在线玩家的完整成就 id 列表，定时只推有变化的玩家（默认 10min） |
| ⚠️ 性能告警 | TPS / 内存超阈值时主动告警，分 warning / critical，带冷却 |
| 🔨 远程白名单管理 | 网站可添加/移除白名单，写入真实的白名单数据 |
| 💾 SQLite 持久化 | 累计数据跨重启保留，**不随换世界/换档清零** |
| 📥 离线缓冲 | 网站断线期间消息入队，重连后自动补发 |
| ⌨️ 管理命令 | 见下方命令表 |

---

## 环境要求

| 项 | 版本 |
| --- | --- |
| Paper | **1.21.11** 或更高（`api-version: 1.21.11`） |
| Java | **21** 或更高 |

---

## 安装

1. 从 Releases 下载最新 jar（或按下方「从源码构建」自行编译）
2. 放进服务器 `plugins/` 目录
3. 重启服务器，首次运行会生成 `plugins/VanillaWhitelist/config.yml`

---

## 配置

`plugins/VanillaWhitelist/config.yml`：

```yaml
websocket:
  mode: "server"                           # server = 监听端口；client = 主动连网站（无需开端口）
  url: ""                                  # 仅 client 模式：如 wss://example.com/vwl
  port: 25585                              # WebSocket 监听端口
  secret: "改成 16 字符以上的随机串"        # 认证密钥（与网站端保持一致）
  enabled: true
  host: "0.0.0.0"                          # 0.0.0.0 = 所有网卡

server:
  id: "main"                               # 多台服务器接同一网站时用于区分

stats:
  push-interval-seconds: 30                # 服务器状态推送间隔
  world-stats-interval-seconds: 300        # 世界统计推送间隔
  player-stats-interval-seconds: 600       # 玩家统计推送间隔

alerts:
  enabled: true
  tps-warning: 15.0                        # TPS 低于此值告警（0 = 关闭）
  tps-critical: 10.0                       # TPS 低于此值升级为 critical
  memory-percent-warning: 85.0             # 内存占用高于此百分比告警
  memory-percent-critical: 95.0
  cooldown-seconds: 300                    # 同类告警冷却（升级不受限）

debug: false
```

修改后执行 `/vwl reload` 或重启服务器。

### ⚠️ 密钥强度要求

以下任一情况，WebSocket 服务**不会启动**并在日志中报错：

- `secret` 为空
- `secret` 仍是出厂默认值 `change-me-to-a-random-string`
- `secret` 长度小于 16 字符

生成随机串：

```bash
openssl rand -hex 24
```

---

## 游戏内命令

主命令 `/vanillawhitelist`，别名 `/vwl`。

| 命令 | 说明 |
| --- | --- |
| `/vwl status` | 查看连接状态与待发队列长度 |
| `/vwl stats` | 立即推送一次全量快照：`server_stats` / `world_stats` / `player_stats_batch` / `player_advancements` |
| `/vwl whitelist add <玩家>` | 手动添加白名单 |
| `/vwl whitelist remove <玩家>` | 手动移除白名单 |
| `/vwl reload` | 重载配置并重启 WebSocket 服务 |

以上均要求管理员权限，支持 Tab 补全。

---

## 网站端接入

网站作为 WebSocket **Client** 连接 `ws://<服务器地址>:25585`，连上后立即发送认证：

```json
{ "type": "auth", "id": "auth-1", "secret": "配置里的 secret" }
```

认证成功后你会**立刻**收到三条基准消息：`server_stats`、`player_advancements`（全量）、`player_stats_batch`，随后按配置的间隔持续推送。

**完整消息格式、字段说明与错误码见 [`PROTOCOL.md`](./PROTOCOL.md)。**
**接入手册（到达时机表、字段语义、数据模型、常见坑、可跑的参考实现）见 [`WEBSITE-GUIDE.md`](./WEBSITE-GUIDE.md)。**

仓库里的 `test-ws.html` 是浏览器版调试工具，可直接连上查看推送内容。

---

## 从源码构建

```bash
./gradlew shadowJar
```

需要 **JDK 21**。产物在 `build/libs/VanillaWhitelist-<版本>.jar`。

---

## 技术栈

- Kotlin 2.0 / Gradle 9
- Paper API 1.21.11
- Java-WebSocket
- Gson
- SQLite（`sqlite-jdbc`）

---

## 许可

MIT
