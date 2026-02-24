# Bridge Mod 完整移植方案 — 自包含开发参考文档

> **用途**：这份文档是从 Mindcraft 项目（Node.js AI bot 框架）提取的完整技术规格。
> 用于在一个全新的独立 Java/Fabric 项目中从零开发 Bridge Mod。
> 阅读者（AI 或人类）只需要这份文档 + Minecraft/Baritone API 文档就能完成开发。
> **Mindcraft 源码路径**：`/Users/yujuncheng/Documents/PLAY_STUDIO/mindcraft/`（如需查阅原始实现）

---

## 目录

1. [项目目标与架构](#1-项目目标与架构)
2. [Java 项目结构](#2-java-项目结构)
3. [WebSocket 协议规范](#3-websocket-协议规范)
4. [状态同步规范](#4-状态同步规范)
5. [命令实现规范](#5-命令实现规范)
6. [查询实现规范](#6-查询实现规范)
7. [事件推送规范](#7-事件推送规范)
8. [Baritone 集成规范](#8-baritone-集成规范)
9. [游戏数据表与常量](#9-游戏数据表与常量)
10. [关键算法移植](#10-关键算法移植)
11. [实现阶段与验证](#11-实现阶段与验证)

---

## 1. 项目目标与架构

### 1.1 背景

Mindcraft 是一个用 LLM（Claude/GPT）控制 Minecraft bot 的 Node.js 框架。目前通过 mineflayer（协议级 headless 客户端）与 MC 服务器交互。

**mineflayer 的局限**：
- 不支持 Mod 服务器（Forge/Fabric modded items/blocks 不可见）
- 寻路用纯 JS 的 mineflayer-pathfinder，遇 2 格墙/游泳/脚手架容易卡住
- 无法使用 Baritone 等成熟 Java 寻路方案

### 1.2 Bridge Mod 的作用

Bridge Mod 是一个 **Fabric Minecraft Mod**，运行在真实 MC 客户端内，通过 **WebSocket** 暴露 API 给 Mindcraft Node.js 进程。

```
┌──────────────────────────────┐
│  Mindcraft (Node.js)         │
│  agent.js / skills.js 等     │
│  通过 BridgeModBot 代理对象   │
│  调用 bot.dig() / bot.chat() │
└─────────────┬────────────────┘
              │ WebSocket (localhost:8089)
              │ JSON 文本帧
              ▼
┌──────────────────────────────┐
│  Bridge Mod (Java/Fabric)    │
│  运行在真实 MC 客户端内       │
│  ├─ WebSocket Server         │
│  ├─ 命令执行器               │
│  ├─ 状态同步推送             │
│  ├─ 事件监听转发             │
│  └─ Baritone 集成            │
└─────────────┬────────────────┘
              │ Minecraft Protocol
              ▼
         MC Server
```

### 1.3 关键设计原则

1. **Bridge Mod 是纯执行层**：不包含 AI 逻辑，只接收命令、执行、返回结果
2. **Mindcraft 端最小改动**：只改 `initBot()` 一个 factory 方法，用 BridgeModBot 代理对象替代 mineflayer bot
3. **协议即接口**：WebSocket JSON 协议是两个项目唯一的耦合点
4. **渐进开发**：可以先实现核心 API（移动、挖掘、聊天），逐步补全高级功能

### 1.4 Mindcraft 端的适配方式（供参考）

Mindcraft 端会创建一个 `BridgeModBot` 代理对象，实现与 mineflayer bot 完全相同的属性/方法/事件接口。所有现有代码（skills.js, modes.js, agent.js, world.js）**无需修改**，因为它们调用的 `bot.dig()`, `bot.entity.position` 等 API 由代理对象透明转发到 Bridge Mod。

```javascript
// Mindcraft 端 initBot() factory — 唯一改动点
export function initBot(username) {
    if (settings.adapter === 'bridge_mod') {
        return createBridgeBot({ username, bridgeUrl: settings.bridge_url });
    }
    // 原有 mineflayer 逻辑不变
    return mineflayer.createBot(options);
}
```

---

## 2. Java 项目结构

### 2.1 技术栈

- **Minecraft 版本**：1.20.4+（Fabric Loader）
- **Mod 框架**：Fabric API
- **构建工具**：Gradle (Kotlin DSL 或 Groovy)
- **WebSocket 库**：Java-WebSocket (`org.java-websocket:Java-WebSocket:1.5.4`) 或 Netty 内置
- **Baritone**：`cabaletta/baritone` — 作为 mod 依赖引入
- **JSON 库**：Gson（Fabric 自带）

### 2.2 推荐包结构

```
src/main/java/com/playStudio/bridgemod/
├── BridgeMod.java              # Mod 入口 (implements ModInitializer)
├── BridgeModClient.java        # 客户端入口 (implements ClientModInitializer)
│
├── websocket/
│   ├── BridgeWebSocketServer.java  # WebSocket 服务端
│   ├── MessageHandler.java         # 消息分发（按 action 路由到对应 handler）
│   └── Protocol.java               # 消息格式定义（请求/响应/事件 POJO）
│
├── handler/                     # 命令处理器（一个文件一类命令）
│   ├── BlockHandler.java        # dig, placeBlock, activateBlock
│   ├── InventoryHandler.java    # equip, unequip, toss, craft, consume
│   ├── MovementHandler.java     # setControl, clearControls, lookAt, look
│   ├── CombatHandler.java       # attack, pvp_attack, pvp_stop
│   ├── ContainerHandler.java    # openContainer, openFurnace, openVillager, trade
│   ├── ChatHandler.java         # chat, whisper
│   ├── NavigationHandler.java   # baritone_goto, baritone_follow, baritone_stop, etc.
│   └── QueryHandler.java        # blockAt, findBlocks, getEntities, etc.
│
├── state/
│   ├── StateSyncManager.java    # 定时推送玩家状态
│   ├── EventForwarder.java      # MC 事件 → WebSocket event 推送
│   └── InventoryTracker.java    # 背包变化追踪
│
├── baritone/
│   └── BaritoneAdapter.java     # Baritone API 封装
│
└── util/
    ├── BlockUtils.java          # 方块查询辅助
    ├── EntityUtils.java         # 实体查询辅助
    └── Vec3d.java               # 坐标工具
```

### 2.3 build.gradle 关键依赖

```groovy
dependencies {
    minecraft "com.mojang:minecraft:1.20.4"
    mappings "net.fabricmc:yarn:1.20.4+build.3:v2"
    modImplementation "net.fabricmc:fabric-loader:0.15.3"
    modImplementation "net.fabricmc.fabric-api:fabric-api:0.92.0+1.20.4"

    // WebSocket
    implementation "org.java-websocket:Java-WebSocket:1.5.4"

    // Baritone (as mod dependency)
    modImplementation "baritone:baritone-api:1.20.4"  // 具体版本看 Baritone releases

    // Gson (Fabric 已自带，但显式声明)
    implementation "com.google.code.gson:gson:2.10.1"
}
```

---

## 3. WebSocket 协议规范

### 3.1 连接

- **默认端口**：8089
- **URL**：`ws://localhost:8089`
- **Bridge Mod 是 WebSocket Server**（Java 端启动 server）
- **Mindcraft 是 WebSocket Client**（Node.js 端连接）
- 连接后由 Mindcraft 发送 `handshake` 命令确认身份

### 3.2 消息格式

所有消息都是 **JSON 文本帧**，不用二进制。

#### Mindcraft → Mod（请求）

```json
{
    "id": "cmd_42",              // 唯一请求 ID（字符串，递增）
    "type": "command",           // "command"（有副作用）或 "query"（只读）
    "action": "dig",             // 动作名称
    "params": {                  // 参数对象
        "x": 100, "y": 64, "z": 200
    }
}
```

#### Mod → Mindcraft（响应）

```json
{
    "id": "cmd_42",              // 对应请求 ID
    "type": "response",
    "success": true,             // 是否成功
    "data": {},                  // 返回数据（成功时）
    "error": "Block not found"   // 错误信息（失败时，可选）
}
```

#### Mod → Mindcraft（主动推送 — 状态同步）

```json
{
    "type": "state_sync",
    "data": { /* 见第 4 节 */ }
}
```

#### Mod → Mindcraft（主动推送 — 事件）

```json
{
    "type": "event",
    "event": "chat",             // 事件名称
    "data": {                    // 事件数据
        "username": "player1",
        "message": "hello"
    }
}
```

### 3.3 超时约定

| 请求类型 | 默认超时 | 说明 |
|---------|---------|------|
| query（只读查询）| 5 秒 | blockAt, findBlocks, getEntities 等 |
| command（普通命令）| 30 秒 | dig, equip, craft 等 |
| command（导航）| 300 秒 | baritone_goto, baritone_follow（长距离导航）|
| command（挖矿）| 600 秒 | baritone_mine（大量方块挖掘）|

### 3.4 握手流程

```
1. Mindcraft 连接 ws://localhost:8089
2. Mindcraft 发送:
   {"id":"h_1", "type":"command", "action":"handshake", "params":{"username":"bot_name","version":"1.20.4"}}
3. Mod 响应:
   {"id":"h_1", "type":"response", "success":true, "data":{"modVersion":"1.0.0","mcVersion":"1.20.4","baritoneAvailable":true}}
4. Mod 开始推送 state_sync 和 event
```

---

## 4. 状态同步规范

### 4.1 推送频率

- **默认每 100ms（2 game ticks）推送一次**
- 如果状态未变化，可以跳过（但最长间隔不超过 1 秒）
- 某些关键属性变化时**立即推送**：health, death, dimension change

### 4.2 完整状态同步数据结构

```json
{
    "type": "state_sync",
    "data": {
        // === 位置与运动（最高频，Mindcraft 40+ 处使用 position）===
        "position": {"x": 100.5, "y": 64.0, "z": 200.3},
        "velocity": {"x": 0.0, "y": -0.0784, "z": 0.0},
        "yaw": 1.57,
        "pitch": 0.0,
        "onGround": true,
        "isInWater": false,
        "isInLava": false,
        "height": 1.8,

        // === 生存状态 ===
        "health": 20.0,         // 0-20, float
        "food": 20,             // 0-20, int
        "saturation": 5.0,      // 0-20, float
        "oxygenLevel": 300,     // 0-300, int (水下呼吸)
        "isSleeping": false,

        // === 游戏信息 ===
        "gameMode": "survival", // "survival", "creative", "adventure", "spectator"
        "dimension": "overworld", // "overworld", "the_nether", "the_end"
        "timeOfDay": 6000,      // 0-24000
        "rainState": 0.0,       // 0.0 = 不下雨, >0 = 下雨
        "thunderState": 0.0,    // 0.0 = 无雷暴, >0 = 雷暴

        // === 手持物品 ===
        "heldItem": {
            "name": "diamond_pickaxe",
            "count": 1,
            "slot": 0,
            "maxDurability": 1561,
            "durability": 1200
        },
        // null 如果手上没东西

        // === 背包摘要（完整背包通过 inventory_update 事件推送）===
        "inventoryUsedSlots": 15,  // 已用槽位数
        "inventoryTotalSlots": 36, // 总槽位数（不含装备）

        // === 装备 ===
        "equipment": {
            "head": {"name": "iron_helmet", "durability": 100},
            "chest": {"name": "iron_chestplate", "durability": 200},
            "legs": null,
            "feet": {"name": "iron_boots", "durability": 150},
            "offhand": null
        }
    }
}
```

### 4.3 Mindcraft 如何使用这些数据

Mindcraft 端的 BridgeModBot 代理对象收到 state_sync 后，会更新以下属性：

```javascript
// 这些属性被 skills.js / modes.js / world.js / agent.js 大量读取
bot.entity.position     = new Vec3(data.position.x, data.position.y, data.position.z);
bot.entity.velocity     = new Vec3(data.velocity.x, data.velocity.y, data.velocity.z);
bot.entity.yaw          = data.yaw;
bot.entity.pitch        = data.pitch;
bot.entity.onGround     = data.onGround;
bot.entity.isInWater    = data.isInWater;
bot.entity.isInLava     = data.isInLava;
bot.entity.height       = data.height;
bot.health              = data.health;
bot.food                = data.food;
bot.oxygenLevel         = data.oxygenLevel;
bot.isSleeping          = data.isSleeping;
bot.game.gameMode       = data.gameMode;
bot.game.dimension      = data.dimension;
bot.time.timeOfDay      = data.timeOfDay;
bot.rainState           = data.rainState;
bot.thunderState        = data.thunderState;
bot.heldItem            = data.heldItem; // {name, count} or null
```

**关键：`bot.entity.position` 是全局最高频 API（40+ 处使用），同步必须低延迟。**

---

## 5. 命令实现规范

以下是 Bridge Mod 必须实现的所有命令。每个命令包含：action 名、参数、预期行为、返回值。

### 5.1 方块交互命令

#### `dig` — 挖掘方块

```
action: "dig"
params: { "x": int, "y": int, "z": int, "forceLook": bool? }
返回: { "success": bool }
```

**行为**：
1. 获取目标坐标的方块
2. 如果 `forceLook` 不为 false，让玩家看向目标方块
3. 选择最佳工具（如果背包有的话）— 调用 `equipBestTool` 逻辑
4. 开始挖掘（`minecraft.getInteractionManager().attackBlock()`）
5. 等待挖掘完成
6. 返回 success

**错误情况**：方块为空气、无法到达、被中断

#### `placeBlock` — 放置方块

```
action: "placeBlock"
params: {
    "x": int, "y": int, "z": int,
    "blockName": "string",
    "face": "string"  // "top", "bottom", "north", "south", "east", "west"
}
返回: { "success": bool }
```

**行为**：
1. 在背包中找到对应的物品（注意方块名→物品名的转换，见第 9.7 节）
2. 装备到主手
3. 计算放置的参考方块和面向量（见第 10.1 节算法）
4. 执行放置
5. 处理特殊方块状态（火把朝向、门的双方块、床的双方块等，见第 10.1 节）

**面向量映射**：
```
"top"    → 参考方块在目标下方 (y-1)，face = UP
"bottom" → 参考方块在目标上方 (y+1)，face = DOWN
"north"  → 参考方块在目标南侧 (z+1)，face = NORTH
"south"  → 参考方块在目标北侧 (z-1)，face = SOUTH
"east"   → 参考方块在目标西侧 (x-1)，face = EAST
"west"   → 参考方块在目标东侧 (x+1)，face = WEST
```

#### `activateBlock` — 右键交互方块

```
action: "activateBlock"
params: { "x": int, "y": int, "z": int }
返回: { "success": bool }
```

**行为**：对目标方块执行右键交互（开门/按钮/拉杆/箱子/工作台等）

#### `stopDigging` — 停止挖掘

```
action: "stopDigging"
params: {}
返回: {}
```

### 5.2 物品管理命令

#### `equip` — 装备物品

```
action: "equip"
params: {
    "itemName": "string",
    "slot": "string"  // "hand", "head", "torso", "legs", "feet", "off-hand"
}
返回: { "success": bool }
```

**slot 映射到 MC 槽位号**（重要！）：
```
"head"     → 装备槽 5 (helmet)
"torso"    → 装备槽 6 (chestplate)
"legs"     → 装备槽 7 (leggings)
"feet"     → 装备槽 8 (boots)
"hand"     → 主手（hotbar 当前选中）
"off-hand" → 副手槽 45
```

**自动检测物品类型→槽位的规则**（当 slot 未指定时）：
```
物品名包含 "leggings"  → slot = "legs"
物品名包含 "boots"     → slot = "feet"
物品名包含 "helmet"    → slot = "head"
物品名包含 "chestplate" 或 "elytra" → slot = "torso"
物品名包含 "shield"    → slot = "off-hand"
其他 → slot = "hand"
```

#### `unequip` — 卸下装备

```
action: "unequip"
params: { "slot": "string" }  // "hand", "head", "torso", "legs", "feet", "off-hand"
返回: { "success": bool }
```

#### `toss` — 丢弃物品

```
action: "toss"
params: {
    "itemName": "string",
    "count": int  // -1 表示全部
}
返回: { "success": bool, "tossed": int }
```

#### `craft` — 合成物品

```
action: "craft"
params: {
    "itemName": "string",
    "count": int,
    "useCraftingTable": bool  // true = 需要 3×3 工作台, false = 2×2 背包合成
}
返回: { "success": bool, "crafted": int }
```

**行为**：
1. 查找合成配方（MC 内置配方系统）
2. 如果需要工作台且 `useCraftingTable` 为 true：
   - 在附近找工作台方块（5 格范围）
   - 如果没有工作台方块，从背包取出工作台方块放置在脚下附近
   - 右键打开工作台
3. 执行合成
4. 如果是自己放置的工作台，合成后捡回来

#### `consume` — 吃/喝手持物品

```
action: "consume"
params: {}
返回: { "success": bool }
```

**行为**：右键使用手持食物/药水，等待消耗动画完成

#### `activateItem` — 使用手持物品（右键空气）

```
action: "activateItem"
params: {}
返回: { "success": bool }
```

#### `useOn` — 对实体使用物品

```
action: "useOn"
params: { "entityId": int }
返回: { "success": bool }
```

### 5.3 移动控制命令

#### `setControl` — 设置移动状态

```
action: "setControl"
params: {
    "control": "string",  // "forward", "back", "left", "right", "jump", "sprint", "sneak"
    "state": bool         // true = 按下, false = 松开
}
返回: {}
```

**行为**：模拟按键状态。在 Fabric 中通过 `MinecraftClient.getInstance().options.forwardKey.setPressed(state)` 等实现。

#### `clearControls` — 清除所有控制状态

```
action: "clearControls"
params: {}
返回: {}
```

**行为**：松开所有移动按键（forward, back, left, right, jump, sprint, sneak 全部 setPressed(false)）

#### `lookAt` — 看向坐标

```
action: "lookAt"
params: { "x": double, "y": double, "z": double }
返回: {}
```

**行为**：计算 yaw/pitch 使玩家朝向目标坐标，平滑转头

#### `look` — 设置朝向

```
action: "look"
params: { "yaw": double, "pitch": double }
返回: {}
```

### 5.4 容器交互命令

#### `openContainer` — 打开容器（箱子/漏斗/发射器等）

```
action: "openContainer"
params: { "x": int, "y": int, "z": int }
返回: {
    "success": bool,
    "containerId": int,
    "slots": [
        {"slot": 0, "name": "diamond", "count": 5},
        {"slot": 1, "name": "iron_ingot", "count": 64},
        ...
    ]
}
```

#### `openFurnace` — 打开熔炉

```
action: "openFurnace"
params: { "x": int, "y": int, "z": int }
返回: {
    "success": bool,
    "furnaceId": int,
    "inputSlot": {"name": "raw_iron", "count": 3} | null,
    "fuelSlot": {"name": "coal", "count": 2} | null,
    "outputSlot": {"name": "iron_ingot", "count": 1} | null,
    "progress": float,  // 0.0-1.0 熔炼进度
    "fuelProgress": float  // 0.0-1.0 燃料进度
}
```

**熔炉交互子命令**（在熔炉打开状态下使用）：

```
action: "furnace_putInput"
params: { "furnaceId": int, "itemName": "string", "count": int }

action: "furnace_putFuel"
params: { "furnaceId": int, "itemName": "string", "count": int }

action: "furnace_takeOutput"
params: { "furnaceId": int }
返回: { "success": bool, "item": {"name": "string", "count": int} | null }

action: "furnace_close"
params: { "furnaceId": int }
```

**Mindcraft 的熔炉使用流程**（供参考）：
1. 打开熔炉
2. 放入原材料到 input slot
3. 放入燃料到 fuel slot
4. 每 1 秒轮询 output slot
5. 如果 11 秒无产出则超时
6. 取出产品
7. 关闭熔炉

**燃料效率值**（每单位可冶炼物品数）：
```
coal = 8, charcoal = 8
blaze_rod = 12
任何含 "log" 或 "planks" 的物品 = 1.5
coal_block = 80
lava_bucket = 100
```

**可冶炼物品表**：
```
直接名称: beef, chicken, cod, mutton, porkchop, rabbit, salmon, tropical_fish, potato, kelp, sand, cobblestone, clay_ball
规则匹配: 任何含 "raw" 的物品 (raw_iron, raw_gold, raw_copper)
规则匹配: 任何含 "log" 的物品 → 输出 charcoal
```

**冶炼产物映射表**：
```
potato       → baked_potato
raw_beef     → steak (注意：不是 cooked_beef)
raw_chicken  → cooked_chicken
raw_cod      → cooked_cod
raw_mutton   → cooked_mutton
raw_porkchop → cooked_porkchop
raw_rabbit   → cooked_rabbit
kelp         → dried_kelp
raw_iron     → iron_ingot
raw_gold     → gold_ingot
raw_copper   → copper_ingot
sand         → glass
cobblestone  → stone
clay_ball    → brick
```

#### `openVillager` — 打开村民交易界面

```
action: "openVillager"
params: { "entityId": int }
返回: {
    "success": bool,
    "trades": [
        {
            "index": 0,
            "inputItem1": {"name": "emerald", "count": 5},
            "inputItem2": null,
            "outputItem": {"name": "iron_pickaxe", "count": 1},
            "disabled": false,
            "uses": 2,
            "maxUses": 12
        },
        ...
    ]
}
```

#### `trade` — 执行交易

```
action: "trade"
params: { "entityId": int, "tradeIndex": int, "count": int }
返回: { "success": bool, "traded": int }
```

#### `containerTransfer` — 容器物品转移

```
action: "containerTransfer"
params: {
    "containerId": int,
    "itemName": "string",
    "count": int,
    "direction": "put" | "take"  // put = 从背包放入容器, take = 从容器取出
}
返回: { "success": bool, "transferred": int }
```

#### `sleep` — 在床上睡觉

```
action: "sleep"
params: { "x": int, "y": int, "z": int }
返回: { "success": bool }
```

### 5.5 通信命令

#### `chat` — 发送聊天消息

```
action: "chat"
params: { "message": "string" }
返回: {}
```

**注意**：如果 message 以 `/` 开头，则是执行命令（如 `/setblock`, `/tp`）

#### `whisper` — 私聊

```
action: "whisper"
params: { "username": "string", "message": "string" }
返回: {}
```

**实现**：`chat("/msg " + username + " " + message)`

### 5.6 战斗命令

#### `attack` — 攻击实体（单次）

```
action: "attack"
params: { "entityId": int }
返回: { "success": bool }
```

**行为**：面向实体 → 挥刀攻击一次

#### `pvp_attack` — 持续 PVP 攻击

```
action: "pvp_attack"
params: { "entityId": int }
返回: { "success": bool }
```

**行为**：持续追踪并攻击目标实体，直到目标死亡或调用 `pvp_stop`。每次攻击间等待攻击冷却（attack cooldown）。

#### `pvp_stop` — 停止 PVP

```
action: "pvp_stop"
params: {}
返回: {}
```

### 5.7 杂项命令

#### `armorEquipAll` — 自动穿戴最佳装甲

```
action: "armorEquipAll"
params: {}
返回: {}
```

**行为**：检查背包中所有装甲物品，自动穿戴防御力最高的组合

#### `equipBestTool` — 选择最佳工具

```
action: "equipBestTool"
params: { "blockName": "string" }
返回: { "success": bool, "equippedTool": "string" | null }
```

**行为**：根据方块类型选择背包中挖掘速度最快的工具装备到主手

#### `collectBlock` — 收集方块（挖掘+拾取）

```
action: "collectBlock"
params: { "x": int, "y": int, "z": int }
返回: { "success": bool }
```

**行为**：
1. 走到方块附近
2. 选择最佳工具
3. 挖掘方块
4. 等待掉落物生成（约 200ms）
5. 拾取掉落物

---

## 6. 查询实现规范

查询是只读操作，不修改游戏状态，需要**低延迟**返回。

### 6.1 `blockAt` — 查询指定位置方块

```
action: "blockAt"
params: { "x": int, "y": int, "z": int }
返回: {
    "name": "stone",              // 方块 ID 名称
    "stateId": 1,                 // 方块状态 ID
    "position": {"x": 100, "y": 64, "z": 200},
    "diggable": true,             // 是否可挖掘
    "boundingBox": "block",       // "block" = 实体方块, "empty" = 空气/液体
    "transparent": false,         // 是否透明
    "drops": ["cobblestone"],     // 挖掘掉落物
    "hardness": 1.5               // 硬度
}
```

**这是最高频查询**（被调用 20+ 处），必须极低延迟。

**优化方案**：Mod 端可以直接从 `World.getBlockState()` 读取，延迟 <1ms。如果 Mindcraft 端做了方块缓存，还可以批量查询减少 WebSocket 往返。

### 6.2 `findBlocks` — 搜索范围内方块

```
action: "findBlocks"
params: {
    "blockNames": ["diamond_ore", "deepslate_diamond_ore"],  // 方块名列表
    "maxDistance": 64,    // 最大搜索距离
    "count": 100          // 最多返回数量
}
返回: {
    "blocks": [
        {"x": 100, "y": 12, "z": 200},
        {"x": 102, "y": 11, "z": 198},
        ...
    ]
}
```

**行为**：在以玩家为中心的 maxDistance 立方体范围内搜索匹配的方块，按距离排序返回。

**注意**：这个操作可能很慢（搜索范围大），应该在服务端线程池中异步执行。

### 6.3 `getEntities` — 获取附近实体

```
action: "getEntities"
params: { "maxDistance": 32 }
返回: {
    "entities": [
        {
            "id": 42,
            "name": "zombie",
            "type": "hostile",      // "hostile", "mob", "animal", "player", "other"
            "position": {"x": 105, "y": 64, "z": 198},
            "health": 20.0,
            "metadata": {
                "isBaby": false
            }
        },
        {
            "id": 55,
            "name": "villager",
            "type": "mob",
            "position": {"x": 110, "y": 64, "z": 195},
            "metadata": {
                "isBaby": false,
                "profession": 5,     // 见第 9.8 节村民职业表
                "level": 2
            }
        },
        ...
    ]
}
```

**实体分类规则**（重要！Mindcraft 用这些分类决定行为）：

```
# 敌对实体判定
type == "hostile" 或 type == "mob"
且 name != "iron_golem" 且 name != "snow_golem"

# 可猎杀动物
name 属于 ["chicken", "cow", "llama", "mooshroom", "pig", "rabbit", "sheep"]
且 isBaby == false（metadata[16] 在原版中，不是 baby）

# 幼崽检测
对于实体的 metadata，第 16 项（Ageable 实体的 age）如果为 true/正数 → 是幼崽
```

### 6.4 `getPlayers` — 获取在线玩家

```
action: "getPlayers"
params: {}
返回: {
    "players": [
        {
            "name": "Steve",
            "uuid": "xxx",
            "position": {"x": 100, "y": 64, "z": 200},  // null 如果不在渲染范围
            "isInRange": true  // 是否在实体渲染范围内
        },
        ...
    ]
}
```

### 6.5 `recipesFor` — 查询合成配方

```
action: "recipesFor"
params: { "itemName": "string" }
返回: {
    "recipes": [
        {
            "ingredients": [
                {"name": "oak_planks", "count": 4}
            ],
            "result": {"name": "crafting_table", "count": 1},
            "requiresCraftingTable": false
        },
        ...
    ]
}
```

### 6.6 `getBiome` — 查询生物群系

```
action: "getBiome"
params: { "x": int, "y": int, "z": int }
返回: { "name": "plains", "id": 1 }
```

### 6.7 `getInventory` — 获取完整背包

```
action: "getInventory"
params: {}
返回: {
    "items": [
        {"slot": 0, "name": "diamond_pickaxe", "count": 1, "durability": 1200, "maxDurability": 1561},
        {"slot": 5, "name": "iron_helmet", "count": 1},
        {"slot": 36, "name": "cobblestone", "count": 64},
        ...
    ],
    "emptySlotCount": 20,
    "equipment": {
        "head": 5,      // slot index
        "chest": 6,
        "legs": 7,
        "feet": 8,
        "offhand": 45
    }
}
```

**MC 背包槽位编号**：
```
0     = 合成输出
1-4   = 合成格 (2×2)
5     = 头盔
6     = 胸甲
7     = 护腿
8     = 靴子
9-35  = 主背包 (27 格)
36-44 = 快捷栏 (9 格)
45    = 副手
```

---

## 7. 事件推送规范

Bridge Mod 必须监听以下 MC 事件并推送给 Mindcraft。

### 7.1 事件清单

#### `login` — 登录成功/世界加载完成

```json
{"type": "event", "event": "login", "data": {"version": "1.20.4"}}
```

**何时触发**：玩家成功进入世界后

#### `spawn` — 重生/维度切换

```json
{"type": "event", "event": "spawn", "data": {}}
```

#### `death` — 玩家死亡

```json
{"type": "event", "event": "death", "data": {"message": "Bot was slain by Zombie", "deathPos": {"x": 100, "y": 64, "z": 200}}}
```

**重要**：Mindcraft 会在 death 事件中清空 resume 动作，重置状态

#### `health` — 生命值/饥饿值/氧气变化

```json
{"type": "event", "event": "health", "data": {"health": 15.0, "food": 18, "oxygen": 300}}
```

**Mindcraft 使用此事件**：
- 记录 `lastDamageTime = Date.now()` 和 `lastDamageTaken = oldHealth - newHealth`
- 用于 self_preservation 模式的低血量逃跑判定

#### `chat` — 聊天消息

```json
{"type": "event", "event": "chat", "data": {"username": "player1", "message": "hello bot"}}
```

**触发条件**：其他玩家发送的公开聊天消息

#### `whisper` — 私聊消息

```json
{"type": "event", "event": "whisper", "data": {"username": "player1", "message": "secret msg"}}
```

#### `messagestr` — 系统消息（原始字符串）

```json
{"type": "event", "event": "messagestr", "data": {"message": "player1 has made the advancement [Getting an Upgrade]"}}
```

**注意**：包括所有聊天、系统消息、actionbar 消息的原始文本

#### `time` — 游戏时间变化

```json
{"type": "event", "event": "time", "data": {"timeOfDay": 6000}}
```

**Mindcraft 用此事件触发时间节点**：
```
timeOfDay 跨越 0    → emit('sunrise')   — Mindcraft 在此提示 "day time"
timeOfDay 跨越 6000 → emit('noon')
timeOfDay 跨越 12000 → emit('sunset')   — Mindcraft 在此提示 "night time, be careful"
timeOfDay 跨越 18000 → emit('midnight')
```

#### `kicked` — 被服务器踢出

```json
{"type": "event", "event": "kicked", "data": {"reason": "You have been kicked"}}
```

#### `end` — 连接断开

```json
{"type": "event", "event": "end", "data": {"reason": "disconnect"}}
```

#### `playerCollect` — 玩家拾取物品

```json
{"type": "event", "event": "playerCollect", "data": {"collector": "bot_name", "itemName": "diamond", "count": 3}}
```

#### `inventory_update` — 背包变化

```json
{
    "type": "event",
    "event": "inventory_update",
    "data": {
        "items": [
            {"slot": 36, "name": "cobblestone", "count": 64},
            {"slot": 37, "name": "diamond", "count": 5},
            ...
        ],
        "emptySlotCount": 20
    }
}
```

**触发时机**：任何物品数量/位置变化时（拾取、丢弃、合成、使用等）

#### `entity_update` — 附近实体变化

```json
{
    "type": "event",
    "event": "entity_update",
    "data": {
        "entities": [ /* 同 getEntities 返回格式 */ ],
        "players": {
            "player1": {"position": {"x": 100, "y": 64, "z": 200}, "entity": { /* ... */ }}
        }
    }
}
```

**推送频率**：每 500ms 或实体列表变化时

#### `path_update` — 导航路径更新（Baritone 状态）

```json
{
    "type": "event",
    "event": "path_update",
    "data": {
        "status": "executing",    // "calculating", "executing", "completed", "failed", "cancelled"
        "goal": {"x": 100, "y": 64, "z": 200},
        "progress": 0.7,          // 0.0-1.0 完成百分比
        "currentPath": [          // 可选：当前路径点
            {"x": 98, "y": 64, "z": 195},
            ...
        ]
    }
}
```

---

## 8. Baritone 集成规范

### 8.1 Baritone API 概述

Baritone 是 Minecraft 最成熟的 Java 寻路 mod。核心优势：
- 原生支持 2 格墙（搭方块翻越）
- 原生游泳导航
- 原生脚手架搭建
- 原生矿物挖掘（自动寻路+挖掘）
- 支持 schematic 建筑

### 8.2 Baritone API 调用方式

```java
// 获取 Baritone 实例
IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();

// 导航
baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(x, y, z));
baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(x, y, z, range));
baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(x, z));  // 忽略 Y
baritone.getFollowProcess().follow(entity -> entity.getName().getString().equals("Steve"));

// 停止
baritone.getPathingBehavior().cancelEverything();

// 挖矿
baritone.getMineProcess().mine(Blocks.DIAMOND_ORE);

// 状态检查
baritone.getPathingBehavior().isPathing();  // 是否在寻路中
baritone.getCustomGoalProcess().isActive(); // 是否有活跃目标
```

### 8.3 mineflayer-pathfinder → Baritone 映射

Mindcraft 端的 BridgePathfinder 代理会将 mineflayer-pathfinder 的调用转换为 Bridge Mod 的 WebSocket 命令。以下是映射关系：

| Mindcraft 调用 | WebSocket 命令 | Baritone 实现 |
|---------------|---------------|--------------|
| `pathfinder.goto(GoalNear(x,y,z,range))` | `baritone_goto {x,y,z,range}` | `GoalNear(x,y,z,range)` |
| `pathfinder.goto(GoalFollow(entity,range))` | `baritone_follow {playerName,range}` | `followProcess.follow()` |
| `pathfinder.setGoal(GoalInvert(goal))` | `baritone_moveAway {x,y,z,distance}` | 计算反向坐标后 `GoalBlock` |
| `pathfinder.stop()` | `baritone_stop` | `cancelEverything()` |
| `pathfinder.isMoving()` | 通过 state_sync/path_update 事件 | `isPathing()` |
| `pathfinder.getPathTo(goal)` | `baritone_pathTo {goal}` | 只计算不执行 |

### 8.4 Baritone Settings 映射

Mindcraft 使用 mineflayer-pathfinder 的 `Movements` 配置来控制导航行为。需要映射到 Baritone settings：

```
action: "baritone_settings"
params: {
    "allowPlace": true,          // 是否允许放方块（搭脚手架）— mineflayer: allow1by1towers
    "allowBreak": true,          // 是否允许破坏方块 — mineflayer: digCost < 100
    "maxFallHeightNoWater": 4,   // 最大摔落高度 — mineflayer: maxDropDown
    "allowParkour": true,        // 允许跑酷跳跃
    "allowSprint": true,         // 允许疾跑
    "allowSwim": true,           // 允许游泳（Baritone 原生支持）
    "blockPlacementPenalty": 2,  // 放方块的代价 — mineflayer: placeCost
    "breakBlockPenalty": 5,      // 破方块的代价 — mineflayer: digCost
    "avoidBreaking": ["glass", "glass_pane"]  // 不应破坏的方块
}
```

对应 Baritone Java Settings：
```java
BaritoneAPI.getSettings().allowPlace.value = params.allowPlace;
BaritoneAPI.getSettings().allowBreak.value = params.allowBreak;
BaritoneAPI.getSettings().maxFallHeightNoWater.value = params.maxFallHeightNoWater;
BaritoneAPI.getSettings().allowParkour.value = params.allowParkour;
BaritoneAPI.getSettings().allowSprint.value = params.allowSprint;
// blocksToAvoidBreaking → 自定义列表
```

### 8.5 导航的双模式策略

Mindcraft 使用两次导航尝试：

**第一次：非破坏性导航**
```json
{"action": "baritone_settings", "params": {
    "allowPlace": true,
    "allowBreak": true,
    "blockPlacementPenalty": 2,
    "breakBlockPenalty": 10,
    "maxFallHeightNoWater": 4,
    "avoidBreaking": ["glass", "glass_pane"]
}}
```

如果第一次失败（超时或无路径），**第二次：破坏性导航**：
```json
{"action": "baritone_settings", "params": {
    "allowPlace": true,
    "allowBreak": true,
    "blockPlacementPenalty": 2,
    "breakBlockPenalty": 5,
    "maxFallHeightNoWater": 4,
    "preventLiquidFlowing": true,
    "avoidBreaking": ["glass", "glass_pane"]
}}
```

### 8.6 Baritone 命令详细实现

#### `baritone_goto` — 导航到坐标

```
action: "baritone_goto"
params: {
    "x": double, "y": double, "z": double,
    "range": int?,  // 可选，到达范围（默认 2）
    "timeout": int? // 可选，超时秒数
}
返回: { "success": bool, "reason": "string?" }
```

**行为**：
1. 设置 Baritone settings（如果之前有 baritone_settings 调用）
2. 创建 GoalNear(x, y, z, range)
3. 启动寻路
4. 等待到达或超时
5. 返回结果

**重要**：这个命令是**阻塞的**（等待导航完成才返回响应），因为 Mindcraft 的 `goToGoal()` 函数是 `await bot.pathfinder.goto(goal)`。

但 Mod 端应该在导航期间持续推送 `path_update` 事件，这样 Mindcraft 可以追踪进度。

同时，必须支持**中断**：如果收到 `baritone_stop` 命令，立即取消当前导航并让之前的 `baritone_goto` 返回 `{success: false, reason: "cancelled"}`。

#### `baritone_follow` — 跟随玩家/实体

```
action: "baritone_follow"
params: { "playerName": "string", "range": int? }
返回: { "success": bool }
```

**行为**：使用 Baritone FollowProcess 持续跟随目标。此命令会**持续执行**直到调用 `baritone_stop` 或目标消失。

#### `baritone_stop` — 停止导航

```
action: "baritone_stop"
params: {}
返回: {}
```

#### `baritone_mine` — Baritone 自动挖矿

```
action: "baritone_mine"
params: { "blockName": "string", "count": int? }
返回: { "success": bool, "mined": int }
```

**行为**：利用 Baritone MineProcess 自动寻找并挖掘指定方块

#### `baritone_moveAway` — 远离指定位置

```
action: "baritone_moveAway"
params: { "x": double, "y": double, "z": double, "distance": int }
返回: { "success": bool }
```

**行为**：
1. 计算远离方向的目标坐标
2. 使用 GoalBlock 导航到远处
3. 这是 Mindcraft `moveAway(bot, distance)` 的实现

---

## 9. 游戏数据表与常量

以下是从 Mindcraft 源码（主要是 `src/utils/mcdata.js`）提取的所有游戏数据常量。Bridge Mod 不一定需要全部，但作为参考保留。

### 9.1 木材类型

```
WOOD_TYPES = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"]
```

**匹配方块后缀**：
```
MATCHING_WOOD_BLOCKS = ["log", "planks", "sign", "boat", "fence_gate", "door", "fence", "slab", "stairs", "button", "pressure_plate", "trapdoor"]
```

例如：`oak_log`, `spruce_planks`, `birch_door`, `jungle_fence_gate`

### 9.2 羊毛颜色

```
WOOL_COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
```

### 9.3 空气/可替换方块

以下方块被视为"空"，可以被放置操作覆盖：

```
EMPTY_BLOCKS = ["air", "water", "lava", "grass", "short_grass", "tall_grass", "snow", "dead_bush", "fern"]
```

### 9.4 需要手动收集的方块

这些方块不能用 `collectBlock.collect()` 自动收集（因为掉落物机制特殊），需要直接挖掘然后拾取：

```
# 完整方块名匹配
MANUAL_COLLECT_FULL = [
    "wheat", "carrots", "potatoes", "beetroots", "nether_wart", "cocoa",
    "sugar_cane", "kelp", "short_grass", "fern", "tall_grass", "bamboo",
    "poppy", "dandelion", "blue_orchid", "allium", "azure_bluet",
    "oxeye_daisy", "cornflower", "lilac", "wither_rose", "lily_of_the_valley",
    "lever", "redstone_wire", "lantern"
]

# 部分名称匹配（substring）
MANUAL_COLLECT_PARTIAL = [
    "sapling", "torch", "button", "carpet", "pressure_plate",
    "mushroom", "tulip", "bush", "vines", "fern"
]
```

### 9.5 重力方块（会掉落的方块）

```
FALL_BLOCKS = ["sand", "gravel"]
# 以及任何名称包含 "concrete_powder" 的方块
```

### 9.6 矿物变种扩展

搜索矿物时自动包含 deepslate 变种：

```
原始矿物 → 扩展搜索
"coal_ore"     → ["coal_ore", "deepslate_coal_ore"]
"iron_ore"     → ["iron_ore", "deepslate_iron_ore"]
"gold_ore"     → ["gold_ore", "deepslate_gold_ore"]
"diamond_ore"  → ["diamond_ore", "deepslate_diamond_ore"]
"lapis_ore"    → ["lapis_lazuli_ore", "deepslate_lapis_lazuli_ore"]
"redstone_ore" → ["redstone_ore", "deepslate_redstone_ore"]
"copper_ore"   → ["copper_ore", "deepslate_copper_ore"]
"emerald_ore"  → ["emerald_ore", "deepslate_emerald_ore"]
```

**Mindcraft 还会自动搜索原木变种**：
```
"log" → 搜索所有 WOOD_TYPES 对应的 XXX_log
```

### 9.7 方块名→物品名的转换

有些方块放置时需要的物品名称与方块名不同：

```
"redstone_wire" → "redstone"      # 红石线 = 红石粉物品
"water"         → "water_bucket"  # 水方块 = 水桶
"lava"          → "lava_bucket"   # 岩浆方块 = 岩浆桶
```

### 9.8 村民职业表

```
VILLAGER_PROFESSIONS = {
    0: "Unemployed",
    1: "Armorer",
    2: "Butcher",
    3: "Cartographer",
    4: "Cleric",
    5: "Farmer",
    6: "Fisherman",
    7: "Fletcher",
    8: "Leatherworker",
    9: "Librarian",
    10: "Mason",
    11: "Nitwit",
    12: "Shepherd",
    13: "Toolsmith",
    14: "Weaponsmith"
}
```

**从实体 metadata 读取**：
- 在原版 Minecraft 中，村民职业存储在 `VillagerData` 中
- 可以通过 `VillagerEntity.getVillagerData().getProfession()` 获取

### 9.9 可猎杀动物

```
HUNTABLE_ANIMALS = ["chicken", "cow", "llama", "mooshroom", "pig", "rabbit", "sheep"]
```

**幼崽判定**：`entity instanceof LivingEntity && ((LivingEntity)entity).isBaby()`

### 9.10 动物掉落物来源

```
ANIMAL_DROP_SOURCES = {
    "raw_beef": "cow",
    "raw_chicken": "chicken",
    "raw_cod": "cod",
    "raw_mutton": "sheep",
    "raw_porkchop": "pig",
    "raw_rabbit": "rabbit",
    "raw_salmon": "salmon",
    "leather": "cow",
    "wool": "sheep"
}
```

### 9.11 门类型（全列表）

Mindcraft 在检测门时会遍历这些类型：

```
DOOR_WOOD_TYPES = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped"]

# 门方块后缀
DOOR_SUFFIXES = ["_door", "_fence_gate", "_trapdoor"]

# 例外：iron_door 是铁门，不检测（不能手动开）
```

### 9.12 放置不需要靠近的物品

这些物品在 Mindcraft 的 placeBlock 逻辑中不需要先走到目标位置附近：

```
DONT_MOVE_FOR = [
    "torch", "redstone_torch", "redstone", "lever", "button",
    "rail", "detector_rail", "powered_rail", "activator_rail",
    "tripwire_hook", "tripwire", "water_bucket", "string"
]
```

### 9.13 时间标签

```
timeOfDay < 6000   → "Morning"
timeOfDay < 12000  → "Afternoon"
其他                → "Night"
```

### 9.14 天气判定

```
thunderState > 0 → "Thunderstorm"
rainState > 0    → "Rain"
其他              → "Clear"
```

---

## 10. 关键算法移植

以下算法从 Mindcraft 的 `skills.js` 和 `modes.js` 中提取。Bridge Mod **不需要实现这些算法**（它们在 Mindcraft Node.js 端运行），但理解它们有助于正确实现 WebSocket 命令。

**核心原则：Bridge Mod 只是执行层，不包含策略逻辑。**

但以下几个算法对 Bridge Mod 的命令实现有直接影响：

### 10.1 方块放置算法（影响 `placeBlock` 命令）

**这是最复杂的算法，直接影响 Bridge Mod 的 placeBlock 实现。**

Mindcraft 的 `placeBlock` 命令发送给 Bridge Mod 时包含：
- 目标坐标 (x, y, z)
- 方块名 (blockName)
- 放置方向 (face: "top"/"bottom"/"north"/"south"/"east"/"west")

**Bridge Mod 需要做的**：

1. **查找参考方块**：放置方块需要"对着哪个方块的哪个面"。给定目标位置和 face，参考方块在 face 的反方向：
   ```
   face = "top"    → 参考方块在 (x, y-1, z)，对着 UP 面
   face = "bottom" → 参考方块在 (x, y+1, z)，对着 DOWN 面
   face = "north"  → 参考方块在 (x, y, z+1)，对着 NORTH 面
   face = "south"  → 参考方块在 (x, y, z-1)，对着 SOUTH 面
   face = "east"   → 参考方块在 (x-1, y, z)，对着 EAST 面
   face = "west"   → 参考方块在 (x+1, y, z)，对着 WEST 面
   ```

2. **参考方块遍历**：如果首选方向的参考方块是空气，按以下顺序尝试其他方向：
   `["top", "bottom", "north", "south", "east", "west"]`

3. **特殊方块状态**（仅在 cheat mode 下通过 `/setblock` 命令实现）：
   ```
   # 火把放在墙上
   "torch" + face是侧面 → /setblock x y z wall_torch[facing=<face>]

   # 按钮/拉杆方向
   face = "top"    → [face=ceiling]
   face = "bottom" → [face=floor]
   face = 侧面     → [facing=<face>]

   # 梯子/中继器/比较器
   → [facing=<face>]

   # 楼梯
   → [facing=<face>]

   # 门（双方块）
   → 下半 /setblock x y z <door>
   → 上半 /setblock x y+1 z <door>[half=upper]

   # 床（双方块）
   → 头部 /setblock x y z <bed>
   → 脚部 /setblock x y z-1 <bed>[part=head]
   ```

4. **普通放置模式（非 cheat）**：
   - 装备方块物品到主手
   - 对参考方块的目标面执行右键放置
   - MC 客户端的 `interactBlock()` API

### 10.2 安全挖掘算法（影响 `dig` 命令）

挖掘前需要检查：
1. 选择最佳工具（根据方块类型）
2. 面向目标方块（`lookAt`）
3. 开始挖掘
4. 等待完成

向下挖掘时的安全检查：
- 目标方块下方是否是岩浆/水
- 目标方块下方是否是虚空（y < -64）
- 目标方块是否是重力方块（sand, gravel, concrete_powder）的上方

### 10.3 自保行为参考（Mindcraft 端逻辑，Bridge Mod 无需实现）

以下是 Mindcraft 的 `self_preservation` 模式逻辑，描述了 Mindcraft 会如何调用 Bridge Mod 的命令：

**溺水逃生（oxygenLevel < 8 且在水中）**：
1. 策略 1：头顶上方是开放空间 → 发送 `setControl("jump", true)` 持续 3 秒 → 然后发送 `baritone_goto(surfacePos)`
2. 策略 2：头顶是实心方块 → 搜索 10×20×10 范围的空气方块 → 发送 `baritone_goto(airPocketPos)`
3. 策略 3：找不到空气 → 发送连续的 `dig(above)` 向上挖 5 格

**火/岩浆逃生**：
1. 检查背包有无 water_bucket → 如果有，使用水桶
2. 搜索附近水源 → 导航过去
3. 如果都没有 → `baritone_moveAway(5)`

**低血量逃跑**：
- 条件：`lastDamageTime < 3秒前` 且 (`health < 5` 或 `lastDamageTaken >= health`)
- 动作：`baritone_moveAway(20)`

**重力方块躲避**：
- 条件：头顶方块名包含 "sand"、"gravel" 或 "concrete_powder"
- 动作：`baritone_moveAway(2)`

### 10.4 卡住检测参考（Mindcraft 端逻辑）

**unstuck 模式**：
```
检测窗口：10 秒
最小进度：1 格（与 pathfinder 目标的距离减少量）
排除动作：smeltItem, craftRecipe, clearFurnace, eat, stay, viewChest

触发后行为：
1. 先执行 baritone_moveAway(5)
2. 如果 moveAway 也失败 → 停止所有导航 + 清除控制状态
```

### 10.5 门检测算法参考

Mindcraft 在导航卡住超过 1.2 秒时会检测周围是否有门：
- 搜索范围：bot 周围 12 个坐标点（上下左右，包含 y+1 和 y-1）
- 门类型：所有 DOOR_WOOD_TYPES 的 door/fence_gate/trapdoor（排除 iron_door）
- 如果找到门 → 对门方块执行 `activateBlock`（右键开门）

---

## 11. 实现阶段与验证

### Phase 1: 基础框架（优先级最高）

**目标**：验证 WebSocket 连接和基础通信可行

**Java 端实现**：
1. `BridgeMod.java` — Mod 入口
2. `BridgeWebSocketServer.java` — WebSocket Server（端口 8089）
3. `MessageHandler.java` — 消息解析和路由
4. `StateSyncManager.java` — 每 100ms 推送 state_sync
5. `ChatHandler.java` — chat/whisper 命令
6. `handshake` 命令

**Mindcraft 端实现**：
1. `src/bridge/protocol.js` — WebSocket Client
2. `src/bridge/bridge_bot.js` — 基础 BridgeModBot（状态属性 + 事件）
3. `src/utils/mcdata.js` — factory 改造

**验证方式**：
- Bot 连接 Bridge Mod，收到 state_sync，position 显示正确
- Bot 通过 chat 命令在 MC 中说话
- MC 中其他玩家说话，Mindcraft 收到 chat 事件

### Phase 2: 世界查询

**Java 端实现**：
1. `QueryHandler.java` — blockAt, findBlocks, getEntities, getPlayers, getBiome, recipesFor, getInventory
2. `EntityUtils.java` — 实体分类（hostile/animal/player）、metadata 提取
3. `BlockUtils.java` — 方块属性提取（diggable, drops, hardness）

**验证方式**：
- Mindcraft 使用 `!nearbyBlocks` 命令正常返回周围方块
- Mindcraft 使用 `!entities` 命令正常列出附近实体
- `bot.blockAt()` 在各种场景下返回正确方块信息

### Phase 3: 导航（Baritone）

**Java 端实现**：
1. `BaritoneAdapter.java` — Baritone API 封装
2. `NavigationHandler.java` — baritone_goto, baritone_follow, baritone_stop, baritone_moveAway, baritone_settings
3. `EventForwarder.java` 添加 path_update 事件

**验证方式**：
- `!goToPlayer Steve` — Bot 导航到玩家
- `!goToPosition 100 64 200` — Bot 导航到坐标
- `!followPlayer Steve` — Bot 跟随玩家
- 测试 2 格墙翻越、水中游泳、搭方块上高处
- 测试中断：导航期间发送 `baritone_stop`

### Phase 4: 方块/物品交互

**Java 端实现**：
1. `BlockHandler.java` — dig, placeBlock, activateBlock, stopDigging
2. `InventoryHandler.java` — equip, unequip, toss, craft, consume, activateItem, useOn
3. `InventoryTracker.java` — 背包变化 → inventory_update 事件

**验证方式**：
- `!collectBlocks stone 10` — Bot 挖掘 10 个石头并拾取
- `!craftRecipe wooden_pickaxe 1` — Bot 合成物品
- `!placeHere cobblestone` — Bot 放置方块
- `!equip diamond_pickaxe` — Bot 装备工具

### Phase 5: 战斗 + 容器 + 完善

**Java 端实现**：
1. `CombatHandler.java` — attack, pvp_attack, pvp_stop
2. `ContainerHandler.java` — openContainer, openFurnace, openVillager, trade, containerTransfer, furnace_* 子命令
3. 完善所有事件推送

**验证方式**：
- Bot 自动攻击附近怪物（self_defense 模式）
- `!smeltItem raw_iron 5` — Bot 使用熔炉冶炼
- `!putInChest cobblestone 64` — Bot 操作箱子
- `!tradeWithVillager` — Bot 与村民交易
- `!goToBed` — Bot 在床上睡觉

### Phase 6: 端到端全功能测试

**验证清单**：
- [ ] 连接/断开/重连
- [ ] 状态同步（position, health, food, oxygen, weather, time）
- [ ] 聊天收发
- [ ] 方块查询（blockAt, findBlocks）
- [ ] 实体查询（getEntities, getPlayers）
- [ ] Baritone 导航（goto, follow, moveAway, stop）
- [ ] 挖掘方块
- [ ] 放置方块（普通方块 + 火把/门等特殊方块）
- [ ] 物品装备/卸下
- [ ] 合成物品
- [ ] 熔炉冶炼
- [ ] 箱子操作
- [ ] 村民交易
- [ ] 战斗（单次攻击 + 持续 PVP）
- [ ] 睡觉
- [ ] 自保行为（溺水/火/低血量触发正确命令序列）
- [ ] 卡住检测（unstuck 正确触发 moveAway）
- [ ] 长时间运行稳定性（1 小时无崩溃）

---

## 附录 A: Mindcraft 完整状态报告格式

Mindcraft 的 `getFullState()` 返回以下 JSON，用于 Web UI 显示。Bridge Mod 的状态同步应该提供所有必要字段来生成此格式：

```json
{
    "name": "bot_name",
    "gameplay": {
        "position": {"x": 100.50, "y": 64.00, "z": 200.30},
        "dimension": "overworld",
        "gamemode": "survival",
        "health": 20,
        "hunger": 20,
        "biome": "plains",
        "weather": "Clear",
        "timeOfDay": 6000,
        "timeLabel": "Morning"
    },
    "action": {
        "current": "Idle",
        "isIdle": true
    },
    "surroundings": {
        "below": "grass_block",
        "legs": "air",
        "head": "air",
        "firstBlockAboveHead": "air (32 blocks up)"
    },
    "inventory": {
        "counts": {"cobblestone": 64, "diamond": 5, "iron_pickaxe": 1},
        "stacksUsed": 15,
        "totalSlots": 36,
        "equipment": {
            "helmet": "iron_helmet",
            "chestplate": "iron_chestplate",
            "leggings": null,
            "boots": "iron_boots",
            "mainHand": "diamond_pickaxe"
        }
    },
    "nearby": {
        "humanPlayers": ["Steve", "Alex"],
        "botPlayers": ["bot2"],
        "entityTypes": ["zombie", "cow", "chicken"]
    },
    "modes": {
        "summary": "self_preservation: ON, unstuck: ON, ..."
    }
}
```

## 附录 B: Mindcraft 源文件索引

供需要查阅原始实现时使用：

| 功能 | 文件 | 关键行号/函数 |
|------|------|-------------|
| Bot 创建 | `src/utils/mcdata.js` | `initBot()` (行 55-84) |
| 所有游戏数据表 | `src/utils/mcdata.js` | 行 1-55, 84-523 |
| 所有技能/动作 | `src/agent/library/skills.js` | 2394 行，见各函数 |
| 方块放置 | `src/agent/library/skills.js` | `placeBlock()` 行 684-864 |
| 合成 | `src/agent/library/skills.js` | `craftRecipe()` 行 79-163 |
| 冶炼 | `src/agent/library/skills.js` | `smeltItem()` 行 190-321 |
| 收集方块 | `src/agent/library/skills.js` | `collectBlock()` 行 466-599 |
| 导航 | `src/agent/library/skills.js` | `goToGoal()` 行 1194-1311 |
| 门检测 | `src/agent/library/skills.js` | `startDoorInterval()` 行 1313-1384 |
| 村民交易 | `src/agent/library/skills.js` | 行 1929-2157 |
| 世界查询 | `src/agent/library/world.js` | 442 行 |
| 自保/卡住/模式 | `src/agent/modes.js` | 622 行 |
| 状态报告 | `src/agent/library/full_state.js` | 88 行 |
| Agent 主类 | `src/agent/agent.js` | 事件注册 行 466-530 |
| 动作管理 | `src/agent/action_manager.js` | 177 行 |
| WebSocket 通信参考 | `src/agent/mindserver_proxy.js` | 137 行 (Socket.io 模式) |

## 附录 C: 开发优先级建议

如果时间有限，按以下优先级实现：

1. **P0（必须有）**：WebSocket 连接 + state_sync + chat + blockAt + baritone_goto/stop
2. **P1（核心功能）**：dig + placeBlock + equip + getInventory + findBlocks + getEntities + baritone_follow
3. **P2（完整体验）**：craft + consume + openFurnace + openContainer + attack + pvp_attack
4. **P3（高级功能）**：trade + baritone_mine + collectBlock + activateBlock + sleep
5. **P4（锦上添花）**：path_update 事件 + entity_update 事件 + 方块缓存优化
