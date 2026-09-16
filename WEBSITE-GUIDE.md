# VanillaWhitelist 网站端开发文档

> **给谁看**：写网站 / 后端的那一方。
> **规范来源**：仓库根目录的 [`PROTOCOL.md`](./PROTOCOL.md) 是唯一契约，字段定义以它为准。
> 本文档讲的是**怎么接、什么时候会收到什么、哪里最容易踩坑**。
>
> 适用版本：**v1.0.4-alpha 及以后**，协议 `protocol_version = 1`。
> 三个实现（Paper 插件 / Fabric 模组 / NeoForge 模组）**行为已经对齐**，网站只写一份代码。

---

## 0. 三十秒

游戏端跑一个 WebSocket **服务**，网站连上去。连上 → 发 `auth` → 之后就是**只读推送 + 少量下行命令**：

- **收**：服务器状态、玩家事件、世界统计、玩家统计、成就明细、性能告警
- **发**：`ping`、`whitelist_add`、`whitelist_remove`

下行只有这三种，其余全是上行。协议里**没有查询类请求**——要最新数据就让管理员在游戏里敲 `/vwl stats`（推一次全量快照），或者等定时推送。

---

## 1. 你要实现哪一种角色

配置项 `mode` 决定谁发起 TCP 连接。**两种模式的消息格式、字段、错误码完全一样**，只有"谁先发 `auth`"不同。

| 模式 | 谁连谁 | 你这边要实现 | 适用场景 |
| --- | --- | --- | --- |
| `server`（**默认**） | **网站连游戏服** | WebSocket **Client** | 游戏服有公网 IP / 已做端口映射 |
| `client` | **游戏服连网站** | WebSocket **Server** | 游戏服在 NAT / 容器 / 家宽里，不想开入站端口 |

> `client` 模式下，游戏端**不监听任何端口**，像普通客户端一样往外连，断线后按指数退避重连（1→2→4→8→16→32→60 秒封顶）。
>
> 代价是：**你必须有一个能常驻的 WebSocket 服务端**。纯 PHP 共享主机那种跑不了常驻进程的环境，只能用 `server` 模式。

下文的例子默认是 `server` 模式（网站当 Client）。

---

## 2. 十分钟接上

```js
const ws = new WebSocket('ws://game.example.com:25585');   // Node 22 / 浏览器都自带 WebSocket

ws.onopen = () => {
  ws.send(JSON.stringify({ type: 'auth', id: 'auth-1', secret: '和 config 里一致的 secret' }));
};

ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.type === 'auth_result') {
    if (!m.success) { console.error('认证失败：', m.error); ws.close(); return; }
    console.log('对端：', m.impl, m.impl_version, '协议版本：', m.protocol_version);
    return;
  }
  // 之后就是各种推送
  console.log(m.type, m);
};
```

认证成功后，**立刻**会收到三条基准消息（见 §5），不用等定时周期。

调试用仓库里的 `test-ws.html`（浏览器打开，填地址和 secret，原样看报文）就够了。

---

## 3. 连接生命周期

```
你连上 ws://<游戏服>:25585
  → 10 秒内必须发出 auth，否则被断开
  → 游戏端校验 secret → 回 auth_result
  → 成功：先补发「网站离线期间缓冲的事件」，再推三条基准：
        server_stats → player_advancements（全量）→ player_stats_batch
  → 双向通信
  → 你断开 / 心跳超时 → 游戏端清理会话
```

**认证成功之前，游戏端会忽略你发的一切**（只有 `ping` 会得到 `pong`）。所以别抢跑。

### 单连接限制

同一时刻只允许 **1 个已认证连接**。**新的连接认证成功后**，旧连接会被踢掉（Paper 端会带关闭码 `4001`；两个模组是直接断开 TCP，没有关闭码）。

> 注意这个顺序是刻意设计的：踢人发生在**认证之后**，所以未认证的人反复握手挤不掉合法连接。

网站端要有**重连 + 重新认证**逻辑；如果同一个 secret 被两处使用（比如你同时开了测试环境），两个客户端会互相踢。

---

## 4. 心跳

| 谁 | 做什么 | 频率 |
| --- | --- | --- |
| 网站 | 发 `{"type":"ping"}` | 每 15 秒 |
| 游戏端 | 回 `{"type":"pong","protocol_version":1}` | 立即 |
| 网站 | 10 秒收不到 `pong` → 主动断开重连 | — |
| 游戏端 | 半开连接检测：30 秒没有任何数据 → 发一次 WebSocket ping 探测；再 30 秒无响应 → 断开 | — |

`ping`/`pong` 是**应用层**心跳，与 WebSocket 协议层的 ping/pong 帧是两回事，后者游戏端也会用（Paper 用 Java-WebSocket 的 `connectionLostTimeout = 60`）。

---

## 5. 消息到达时间表（★ 最重要的一张表）

| 消息 | 什么时候来 | 认证后立刻来吗 | 内容范围 |
| --- | --- | --- | --- |
| `auth_result` | 你发 auth 后立刻 | — | 握手结果 |
| `server_stats` | 每 `push-interval-seconds`（默认 **30 秒**） | ✅ | 当前状态**全量** |
| `player_event` | 事件发生时**实时** | ❌ | **单条事件**（join / leave / death / dimension_change / advancement） |
| `world_stats` | 每 `world-stats-interval-seconds`（默认 **300 秒**） | ❌ | 当前状态**全量** |
| `player_stats_batch` | 每 `player-stats-interval-seconds`（默认 **600 秒**） | ✅ | **仅在线玩家**；Paper 端在玩家退出时还会额外补一条"只有该玩家"的 |
| `player_advancements` | 每 `player-stats-interval-seconds`（默认 **600 秒**） | ✅（这一条是全量基准） | 定时推送**只含有变化的在线玩家** |
| `performance_alert` | TPS / 内存越线时（跟着 30 秒的检查节奏） | ❌ | 越线指标 + 冷却 |

**两个直接结论**：

1. **离线时长 / 击杀 / 死亡 这三个数只能从 `player_stats_batch` 拿**（协议里没有击杀事件，见 §9）。默认 10 分钟才来一次 —— 所以网站刚接上时如果没有这三条基准，会长时间显示 0。
2. `/vwl stats` 会让游戏端**立刻推一次全量快照**（`server_stats` + `world_stats` + `player_stats_batch` + `player_advancements`）。开发调试时用它，不用等 10 分钟。

---

## 6. 消息详解

所有出站消息都带 `protocol_version`（当前 `1`），包括 `pong` 和 `whitelist_result`。

### 6.1 `auth_result`

```json
{ "type": "auth_result", "id": "auth-1", "success": true,
  "protocol_version": 1, "impl": "paper", "impl_version": "1.0.4-alpha" }
```

失败：`{ "type": "auth_result", "id": "auth-1", "success": false, "error": "INVALID_SECRET" }`，然后连接被关掉。

- `impl`：`paper` / `fabric` / `neoforge` —— 用来区分平台，**不要**用它区分字段（三端字段一致）。
- `impl_version`：如 `1.0.4-alpha`，可用于版本提示。

### 6.2 `server_stats`（默认 30 秒 + 认证后立即）

```json
{
  "type": "server_stats", "protocol_version": 1,
  "server_id": "main",
  "tps": 19.8, "mspt": 12.3,
  "memory_used": 2048, "memory_max": 4096,
  "loaded_chunks": 12500, "entity_count": 3420,
  "online_count": 15,
  "uptime_seconds": 86400,
  "players": [
    { "name": "Steve", "uuid": "…", "dimension": "overworld", "x": 100.5, "y": 64.0, "z": -203.3 }
  ]
}
```

| 字段 | 单位 / 含义 | 注意 |
| --- | --- | --- |
| `server_id` | 多台服务器接同一网站时的区分标识 | 配置项 `server.id` / `serverId` |
| `tps` | 保留 1 位小数 | Paper 取 **1 分钟平均**；模组由 MSPT 推算，**两者算法不同**，别拿来做跨端对比 |
| `mspt` | 平均每 tick 毫秒 | |
| `memory_used` / `memory_max` | **MB**（JVM 堆） | 不是物理内存 |
| `loaded_chunks` / `entity_count` | **所有世界求和** | 会随玩家移动剧烈波动 |
| `uptime_seconds` | 运行时长（秒） | Paper 取的是 **JVM 进程**运行时长，模组取的是**服务端启动**时刻 —— 可用作"服务器是否重启过"的判断，不要当精确值 |
| `players[]` | **仅在线玩家** | 坐标保留 1 位小数；`dimension` 取值见下 |

`dimension` 归一化取值：`overworld` / `the_nether` / `the_end`，其他维度取维度路径名。

### 6.3 `player_event`（实时）

```json
{ "type":"player_event","event":"join","player_name":"Steve","player_uuid":"…" }
{ "type":"player_event","event":"leave","player_name":"Steve","player_uuid":"…","playtime_seconds":3600 }
{ "type":"player_event","event":"death","player_name":"Steve","player_uuid":"…","cause":"fall" }
{ "type":"player_event","event":"dimension_change","player_name":"Steve","player_uuid":"…","from":"overworld","to":"the_nether" }
{ "type":"player_event","event":"advancement","player_name":"Steve","player_uuid":"…","advancement":"minecraft:story/mine_diamond" }
```

- `leave.playtime_seconds` 是**本次会话**的秒数，不是累计值。
- `death.cause` 是伤害类型 key，**去掉了 `minecraft:` 前缀**（如 `fall` / `mob` / `player`）。
- `advancement.advancement` 是**完整 id**（含命名空间）。
- 事件消息在网站离线时**会入队**，重连后补发（见 §9.5）。

### 6.4 `world_stats`（默认 5 分钟）

```json
{ "type":"world_stats","protocol_version":1,
  "worlds":[
    { "name":"world","type":"overworld","explored_chunks":12500,
      "total_blocks_placed":1500000,"total_blocks_broken":800000,
      "total_players_joined":120,"total_advancements":340 }
  ] }
```

| 字段 | 含义 | 注意 |
| --- | --- | --- |
| `name` | 平台各自的世界标识 | Paper 是**世界文件夹名**，模组是**维度路径名**，跨平台不一致，别当主键 |
| `type` | 归一化维度名 | **建议用它做归类主键** |
| `explored_chunks` | **当前已加载区块数** | 原版没有"已探索区块"API，这是近似值，会上下波动 |
| `total_blocks_placed` / `total_blocks_broken` | **按该世界分别统计** | 真实的世界级累计 |
| `total_players_joined` / `total_advancements` | **服务器级全局值** | 每个世界条目**内容相同**，别重复累加 |

### 6.5 `player_stats_batch`（默认 10 分钟 + 认证后立即）

```json
{ "type":"player_stats_batch","protocol_version":1,
  "players":[
    { "uuid":"…","name":"Steve",
      "playtime_seconds":72000, "deaths":15, "kills":230,
      "blocks_placed":50000, "blocks_broken":30000,
      "distance_walked":125000.5, "achievements_count":25,
      "first_join":"2026-01-15T10:00:00Z", "last_join":"2026-07-06T14:30:00Z" }
  ] }
```

- **只包含在线玩家**。玩家退出后不会再有他的条目 —— 你的库要**保留**最后一次的值，不能因为"这批里没有"就删。
- `kills` = 击杀生物 + 击杀玩家。
- `distance_walked` 单位是**方块**（原版 `walk_one_cm` ÷ 100），1 位小数。
- `playtime_seconds` 是**累计**在线秒数，**包含当前会话**。
- `first_join` / `last_join` 是 ISO-8601 UTC 字符串；**可能是空字符串**（从未记录到时）。
- **按 `uuid` 逐条 merge**，不要用整批替换（见 §10）。

### 6.6 `player_advancements`（默认 10 分钟 + 认证后立即）

```json
{ "type":"player_advancements","protocol_version":1,
  "players":[ { "uuid":"…","name":"Steve","total":26,
                "advancements":["minecraft:story/root","minecraft:story/mine_diamond"] } ] }
```

- `advancements` 是该玩家**完整的**已达成列表 —— 可以直接**整体替换**这个玩家的成就集合（这一条是例外，见 §10）。
- 定时推送时**只包含有变化的在线玩家**；一个都没变就**不发这条消息**。
- 认证成功后收到的那一条是**全量基准**。
- 有个配合的实时事件：`player_event` / `event: advancement`。

### 6.7 `performance_alert`

```json
{ "type":"performance_alert","protocol_version":1,"severity":"warning",
  "alerts":[ { "metric":"tps","value":12.3,"threshold":15.0 } ],
  "tps":12.3,"mspt":81.2,"memory_used":3700,"memory_max":4096 }
```

- `severity`：`warning` / `critical`，取所有触发项里最高的。
- `metric`：`tps` / `memory_percent`。
- 同类告警在冷却期（默认 300 秒）内不重复推；**warning → critical 的升级会立刻推**，不受冷却限制。
- 指标恢复正常后再次恶化，会重新告警。

---

## 7. 白名单操作（你唯一能下发的写操作）

```json
// 你发
{ "type":"whitelist_add",    "id":"wl-1", "player_name":"Steve" }
{ "type":"whitelist_remove", "id":"wl-2", "player_name":"Steve" }

// 游戏端回
{ "type":"whitelist_result", "protocol_version":1, "id":"wl-1",
  "action":"whitelist_add", "success":true, "player_name":"Steve" }
```

失败时追加 `"error":"<错误码>"`。

- `id` 由你生成，游戏端**原样回传**，用来和请求配对。
- **`player_name` 必须是 3–16 个字符，且只含 `A-Z a-z 0-9 _`。** 不合法会直接被拒。
- 游戏端侧还有一条约束：**服务器必须开启白名单**（`white-list=true`），否则一律返回 `WHITELIST_DISABLED`。
- `player_uuid` 是**可选**字段，但**三端行为不一致**：Paper 会用它，两个模组**会忽略它**。
  → **建议只用 `player_name`**，不要依赖 `player_uuid`。
- 加白名单会去解析玩家资料（可能是一次网络查询），**回包不是瞬时的**，几百毫秒到数秒都正常，别设太短的超时。

### 错误码（三端一致，共 12 个）

| 错误码 | 含义 |
| --- | --- |
| `INVALID_SECRET` | 认证密钥错误 |
| `INVALID_JSON` | 收到非法 JSON |
| `MISSING_TYPE` | 消息缺少 `type` |
| `NOT_AUTHENTICATED` | 未认证就发操作请求 |
| `PLAYER_NAME_EMPTY` | `player_name` 为空 |
| `PLAYER_NAME_INVALID_LENGTH` | 长度不在 3–16 |
| `PLAYER_NAME_INVALID_CHARS` | 含非法字符 |
| `PLAYER_LOOKUP_FAILED` | 无法解析该玩家资料 |
| `ALREADY_WHITELISTED` | 已在白名单中 |
| `NOT_WHITELISTED` | 不在白名单中（移除时） |
| `WHITELIST_DISABLED` | 服务端未开启白名单 |
| `INTERNAL_ERROR` | 内部错误 |

---

## 8. 网站端必须注意的坑

### 8.1 没有"击杀事件"，这三个数只能用 `player_stats_batch`

协议里只有 `death` 事件，**没有 kill 事件**。所以：

- **在线时长** → 用 `player_stats_batch.playtime_seconds`（累计，含当前会话）。`leave` 事件的 `playtime_seconds` 是**本次会话**，两者含义不同，别混用。
- **死亡数** → `player_stats_batch.deaths`（累计）。`death` 事件只用于"实时播报"，它可以用来做时间线，但**累计值以 batch 为准**。
- **击杀数** → **只能**用 `player_stats_batch.kills`。没有任何事件能让你在本地累加出来。

如果你发现这三项一直是 0，先确认网站是不是**从没收到过 `player_stats_batch`**（默认 10 分钟一条）。让管理员敲一次 `/vwl stats` 立刻就能拿到。

### 8.2 `player_stats_batch` 是"部分更新"，不是"全量替换"

它只含**在线玩家**，而且 Paper 端在玩家退出时还会补一条**只含该玩家**的批次。所以必须：

```js
// 正确：按 uuid 逐条 merge
for (const p of m.players) upsertPlayer(p);

// 错误：整表替换（一有人退出，其余玩家就全没了）
players = m.players;
```

`player_advancements` 是**唯一**可以"整体替换"的（它的 `advancements` 数组是完整列表）—— 但也要**按玩家**替换，不是换掉整张表。

### 8.3 世界统计里有两个字段是"服务器级"

`world_stats.worlds[]` 每一条里的 `total_players_joined` 和 `total_advancements` **都是全局值、内容相同**。
如果你按世界分别入库再求和，会得到"人数 × 世界数"的错误结果。取任意一条即可。

### 8.4 `explored_chunks` 是近似值

原版没有"已探索区块"API，这里给的是**当前已加载区块数**，玩家一走动就会大幅波动。别把它当"地图探索进度"。

### 8.5 离线补发的消息是**旧**的

游戏端在网站离线期间：

- **事件类消息**（join / leave / death / dimension_change / advancement）→ **入队**（上限 1000 条，超出丢最旧的），持久化在 SQLite，重连认证后**先补发**；
- **定时快照**（server_stats / world_stats / player_stats_batch / performance_alert）→ **不缓存**（没连接时直接跳过采集），避免重连后收到一批过期遥测。

所以重连后的顺序是：**旧事件 → 三条新鲜基准**。网站端要：

- 事件按到达顺序处理即可（它们本来就是历史事实）；
- 快照**永远以最新一条为准**，别用"先到先得"；
- 如果你改过推送字段，**升级插件后记得清一次队列**，否则会先收到一条旧格式的报文。

### 8.6 断开原因不要依赖关闭码

- **Paper 端**（Java-WebSocket）会给关闭码：`4000` 会话已失效、`4001` 被新连接替换、`4002` 认证超时、`4003` 密钥错误、`4004` 未认证连接过多。
- **两个模组**是自己实现的最小 WebSocket，关闭时**直接断 TCP，没有关闭码**。

所以重连逻辑请统一按"**连接断了就退避重连**"处理。

### 8.7 接口没有"查询"，只有推送

协议里没有任何 request/response 式的查询。想要立即刷新数据：

- 让管理员在游戏里敲 `/vwl stats`（推一次全量快照）；
- 或者等下一次定时推送。

网站端**不要**设计成"打开页面才去拉数据"——数据是推过来的，你应该**持续接收 + 落库 + 页面读库**。

### 8.8 `protocol_version` 要真的检查

每条消息都带。遇到不认识的版本时给用户一个明确提示，而不是静默出错。当前是 `1`。

---

## 9. 推荐的数据模型

最小可用的一组表：

```sql
-- 服务器（由 server_stats.server_id 归档）
CREATE TABLE server (
  server_id     TEXT PRIMARY KEY,
  impl          TEXT,            -- paper / fabric / neoforge
  impl_version  TEXT,
  last_seen     INTEGER,
  online_count  INTEGER,
  tps           REAL, mspt REAL,
  memory_used   INTEGER, memory_max INTEGER,
  loaded_chunks INTEGER, entity_count INTEGER,
  uptime_seconds INTEGER
);

-- 玩家（按 uuid 合并，跨服务器时主键用 (server_id, uuid)）
CREATE TABLE player (
  server_id         TEXT NOT NULL,
  uuid              TEXT NOT NULL,
  name              TEXT,
  playtime_seconds  INTEGER DEFAULT 0,   -- 累计，含当前会话
  deaths            INTEGER DEFAULT 0,
  kills             INTEGER DEFAULT 0,
  blocks_placed     INTEGER DEFAULT 0,
  blocks_broken     INTEGER DEFAULT 0,
  distance_walked   REAL    DEFAULT 0,
  achievements_count INTEGER DEFAULT 0,
  first_join        TEXT,
  last_join         TEXT,
  updated_at        INTEGER,
  PRIMARY KEY (server_id, uuid)
);

-- 成就明细：按玩家整体替换
CREATE TABLE player_advancement (
  server_id TEXT NOT NULL, uuid TEXT NOT NULL, advancement TEXT NOT NULL,
  PRIMARY KEY (server_id, uuid, advancement)
);

-- 事件流（只追加）
CREATE TABLE player_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  server_id TEXT, uuid TEXT, name TEXT,
  event TEXT,              -- join/leave/death/dimension_change/advancement
  cause TEXT, from_dim TEXT, to_dim TEXT, advancement TEXT,
  playtime_seconds INTEGER,
  occurred_at INTEGER
);

-- 世界统计
CREATE TABLE world (
  server_id TEXT NOT NULL, name TEXT NOT NULL, type TEXT,
  explored_chunks INTEGER,
  total_blocks_placed INTEGER, total_blocks_broken INTEGER,
  total_players_joined INTEGER, total_advancements INTEGER,
  updated_at INTEGER,
  PRIMARY KEY (server_id, name)
);
```

**合并规则一句话**：

- `server_stats` / `world_stats` → **整体覆盖**当前状态；
- `player_stats_batch` → 按 uuid **逐条覆盖**（缺的玩家保留旧值）；
- `player_advancements` → 按 uuid **整体替换**该玩家的成就集合；
- `player_event` → **只追加**，不改累计值（累计值以 batch 为准）。

---

## 10. 完整参考实现（Node 22，零依赖）

```js
// site.js —— 最小可用的网站端：连接、认证、心跳、落库、下发白名单
const URL    = process.env.VWL_URL    || 'ws://127.0.0.1:25585';
const SECRET = process.env.VWL_SECRET || 'change-me-to-a-random-string';

let ws = null, backoff = 1000, pingTimer = null, pongTimer = null;

function connect() {
  ws = new WebSocket(URL);

  ws.onopen = () => {
    backoff = 1000;
    send({ type: 'auth', id: 'auth-' + Date.now(), secret: SECRET });

    clearInterval(pingTimer);
    pingTimer = setInterval(() => {
      send({ type: 'ping' });
      clearTimeout(pongTimer);
      pongTimer = setTimeout(() => { console.warn('10 秒没收到 pong，重连'); ws.close(); }, 10000);
    }, 15000);
  };

  ws.onmessage = (ev) => {
    let m; try { m = JSON.parse(ev.data); } catch { return; }
    if (m.type === 'pong') { clearTimeout(pongTimer); return; }
    handle(m);
  };

  ws.onclose = () => {
    clearInterval(pingTimer); clearTimeout(pongTimer);
    console.warn('连接断开，' + backoff + 'ms 后重连');
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 60000);
  };

  ws.onerror = () => {};   // onclose 会跟着触发，统一在那里重连
}

function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }

function handle(m) {
  if (m.protocol_version !== undefined && m.protocol_version !== 1) {
    console.warn('协议版本不一致：对端 ' + m.protocol_version + '，本端 1');
  }
  switch (m.type) {
    case 'auth_result':
      if (!m.success) { console.error('认证失败：' + m.error); ws.close(); return; }
      console.log('已认证：' + m.impl + ' ' + m.impl_version);
      break;

    case 'server_stats':        upsertServer(m); break;
    case 'world_stats':         for (const w of m.worlds) upsertWorld(m.server_id, w); break;
    case 'player_stats_batch':  for (const p of m.players) upsertPlayer(p); break;   // 只更新，不删
    case 'player_advancements': for (const p of m.players) replaceAdvancements(p); break;
    case 'player_event':        appendEvent(m); break;                                // 只追加
    case 'performance_alert':   console.warn('性能告警 ' + m.severity, m.alerts); break;
    case 'whitelist_result':    console.log('白名单结果', m.id, m.success, m.error || ''); break;
    default:                    console.log('未处理的消息类型：' + m.type);
  }
}

// —— 下面是占位实现，换成你的数据库 ——
function upsertServer(m) { /* … */ }
function upsertWorld(id, w) { /* … */ }
function upsertPlayer(p) { /* … */ }
function replaceAdvancements(p) { /* … */ }
function appendEvent(m) { /* … */ }

// 下发白名单
function addToWhitelist(name)    { send({ type: 'whitelist_add',    id: 'wl-' + Date.now(), player_name: name }); }
function removeFromWhitelist(name){ send({ type: 'whitelist_remove', id: 'wl-' + Date.now(), player_name: name }); }

connect();
```

> Node 22 自带全局 `WebSocket`，不需要 `ws` 包。浏览器里同样能跑（注意浏览器不允许自定义 header，本协议也不需要）。

---

## 11. 调试与排错

| 现象 | 原因 | 怎么办 |
| --- | --- | --- |
| 连不上 | 游戏端没监听 | 看游戏服日志有没有 `WebSocket server started`。**密钥不合格（空 / 出厂默认值 / 少于 16 字符）时，WebSocket 会拒绝启动**，游戏服本身照常运行 |
| 连不上 | 端口冲突 | `websocket.port` 与游戏端口相同时，游戏端会**拒绝启动 WebSocket** 并打红色日志 |
| 连上就被踢 | 有另一个客户端在用同一个 secret | 单连接限制，先关掉另一个 |
| `auth_result.success = false` | secret 不一致 | 两端配置必须完全一致（含大小写） |
| 有 `server_stats` 但玩家数据全 0 | 从没收到 `player_stats_batch` | 默认 10 分钟一条；敲 `/vwl stats` 立刻拿全量快照 |
| 数据"卡住"不更新 | 你可能整表替换了玩家列表 | 改成按 uuid merge |
| 收到一条缺字段的旧报文 | 升级插件后队列里有旧格式消息 | 清空服务端 SQLite 的 `message_queue`（升级前也应清一次） |
| 重连后一堆历史事件 | 正常：离线期间事件会入队补发 | 按到达顺序处理；快照以最新为准 |

**两个现成的调试工具**：

- `test-ws.html` —— 浏览器打开，填地址和 secret，原样看所有报文；
- 测试区的 `ws-test.mjs` —— 端到端测试客户端（`VWL_IMPL` / `VWL_SECRET` 环境变量）。

---

## 12. 版本兼容策略

- 每条出站消息都带 `protocol_version`；`auth_result` 里还带 `impl` / `impl_version` 供你识别对端。
- **纯新增可选字段不升版本**，只在**不兼容改动**时才递增。
- 建议：启动或认证后把 `impl / impl_version / protocol_version` 存下来，出问题时能一眼看出对端是谁、什么版本。
- 三端一致性由仓库里的 `check-protocol.ps1` 把关（三份 `PROTOCOL.md` 逐字节相同 + 版本号一致）。

---

## 附：字段速查

| 消息 | 关键字段 | 更新策略 |
| --- | --- | --- |
| `server_stats` | `server_id` `tps` `mspt` `memory_*` `loaded_chunks` `entity_count` `online_count` `uptime_seconds` `players[]` | 覆盖 |
| `world_stats` | `worlds[].name/type/explored_chunks/total_blocks_placed/total_blocks_broken/total_players_joined/total_advancements` | 覆盖 |
| `player_stats_batch` | `players[].uuid/name/playtime_seconds/deaths/kills/blocks_placed/blocks_broken/distance_walked/achievements_count/first_join/last_join` | 按 uuid merge |
| `player_advancements` | `players[].uuid/name/total/advancements[]` | 按 uuid 整体替换 |
| `player_event` | `event` + `player_name` `player_uuid`（+ `playtime_seconds` / `cause` / `from`+`to` / `advancement`） | 只追加 |
| `performance_alert` | `severity` `alerts[]` `tps` `mspt` `memory_*` | 只记录 |

**唯一契约仍然是 [`PROTOCOL.md`](./PROTOCOL.md)。** 本文档与它冲突时，以它为准。
