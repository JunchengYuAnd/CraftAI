# Phase 4: Block/Item Interaction Commands (v1 - 原始计划)

## Context

Phase 3 完成了完整的 A* 寻路系统（22 种移动类型，挖掘/搭桥/跑酷/垂直下挖）。
但 bot 除了移动和基础的 break/place/dig 外，无法与世界进行更丰富的交互：
不能开门、不能攻击怪物、不能吃东西、不能丢物品、不能开箱子、不能合成。

Phase 4 补全这些交互能力，为后续 Mindcraft AI 集成打下基础。

## 修改的文件

1. **`bot/FakePlayer.java`** — 新增 7+ 个动作 API
2. **`handler/BotHandler.java`** — 新增 ~10 个 WebSocket 命令处理器

## 实现步骤（按依赖顺序）

---

### Step 1: `bot_activate` — 右键交互方块（开门/按钮/箱子/拉杆） ✅ 已完成

**FakePlayer 新增：**
```java
public InteractionResult activateBlock(BlockPos target, @Nullable Direction face)
```
- 调用 `gameMode.useItemOn()`，与 `placeBlock` 相同的底层 API
- 区别：不要求手持方块，目的是交互而非放置
- 自动 `lookAt(target)` + `swing(MAIN_HAND)`
- 距离检查 4.5 blocks，超出范围自动寻路

**BotHandler 命令：**
- Params: `{name, x, y, z, face?}`
- Response: `{result, blockName, position}`
- 超出范围自动 pathfind → 到达后执行

**已提交：** commit `2f557be`

---

### Step 2: `bot_attack` — 近战攻击实体

**原始设计（简单版）：**
```java
public void attackEntity(Entity target)    // lookAt + swing + attack(target)
public void lookAtEntity(Entity target)    // 对准实体身体中心
```
- 使用 Carpet mod 模式：`player.attack(target)` → `swing()` → `resetAttackStrengthTicker()`
- 单次攻击，不含追击逻辑

**用户反馈：需要完整战斗循环**
- 自动寻路追击
- 攻击冷却管理（getAttackStrengthScale >= 0.9f）
- 目标有效性检测（isAlive, isRemoved, distance）
- Sprint 优化（远处冲刺，近处行走）

**参考实现：**
- Carpet mod: `player.attack(target)` + `resetAttackStrengthTicker()`
- mineflayer-pvp: GoalFollow + tick-based cooldown + entityGone event

---

### Step 3: `bot_use_item` / `bot_consume` — 使用手持物品

**分两种情况：**

**A. 即时使用（`bot_use_item`）：** 末影珍珠、打火石、雪球等
- 调用 `gameMode.useItem(bot, level, hand)` → 立即返回结果
- **同步**命令
- Params: `{name, hand?}`
- Response: `{itemName, result}`

**B. 持续使用（`bot_consume`）：** 食物、药水、牛奶桶
- 调用 `bot.startUsingItem(hand)` 启动 LivingEntity 的使用循环
- 每 tick 由 `LivingEntity.tick()` → `tickActiveItemStack()` 自动倒计时
- FakePlayer.tick() 里新增 `tickUsingItem()` 监测 `isUsingItem()` 状态
- 完成时回调通知
- **异步**命令（类似 bot_dig 模式）

**FakePlayer 新增：**
```java
public void startUsingItemAction(InteractionHand hand, BiConsumer<Boolean, String> callback)
public void abortUsingItem()
private void tickUsingItem()  // 在 tick() 中调用
```

新增状态字段：`isUsingItem`, `usingItemCallback`, `usingItemTicksRemaining`

- Params: `{name, hand?}`
- Response: `{itemName, reason, foodLevel, saturation}`

---

### Step 4: `bot_drop` — 丢弃物品

**FakePlayer 新增：**
```java
public int dropFromSlot(int inventorySlot, int count)
```
- 从指定槽位取出 N 个物品，调用 `this.drop(stack, false)` 生成掉落物实体
- count = -1 → 丢弃整组

**BotHandler 命令：**
- Params: `{name, slot, count?}` 或 `{name, item, count?}`（按物品名搜索）
- Response: `{dropped, itemName, remainingInSlot}`
- **同步**命令

---

### Step 5: `bot_look` — 设置朝向

**FakePlayer 新增：**
```java
public void lookDirection(float yaw, float pitch)  // 直接设置角度
public void lookAtPos(double x, double y, double z) // 看向世界坐标
```
- 设置 `yRot/xRot` + `yRotO/xRotO`（防止插值抖动）
- 调用 `broadcastPositionToClients()` 发送头部旋转包

**BotHandler 命令：**
- Params: `{name, yaw, pitch}` 或 `{name, x, y, z}`
- Response: `{yaw, pitch}`
- **同步**命令

---

### Step 6: `bot_equip_item` — 按名称装备物品（含护甲/副手）

**FakePlayer 新增：**
```java
public EquipmentSlot equipItemByName(String itemName)
```
- 搜索全背包找匹配物品
- 用 `LivingEntity.getEquipmentSlotForItem(stack)` 判断目标槽位
- 头盔/胸甲/护腿/靴子 → 直接 `setItem` 到 armor 列表
- 武器/工具 → 移到快捷栏并切换
- 盾牌 → 移到副手
- 广播装备变更包

**BotHandler 命令：**
- Params: `{name, item}` — item 为物品注册名如 `"diamond_chestplate"`
- Response: `{equipped, itemName, slot}`
- **同步**命令

---

### Step 7: 容器交互（依赖 Step 1）

**`bot_container_open`：** 右键容器方块 + 返回内容
- Params: `{name, x, y, z}`
- 内部调用 `activateBlock(pos)` → 检查 `containerMenu` 变化
- Response: `{containerType, slotCount, slots: [{slot, name, count}]}`

**`bot_container_close`：** 关闭当前容器
- 调用 `bot.doCloseContainer()`
- Params: `{name}`

**`bot_container_transfer`：** 从容器取物品到背包（Shift+Click）
- 调用 `containerMenu.quickMoveStack(bot, slotIndex)`
- Params: `{name, slot}` 或 `{name, item, count?}`
- Response: `{transferred: [{slot, name, count}], totalCount}`

**`bot_container_put`：** 从背包放物品到容器
- 用 `containerMenu.clicked()` 模拟 Pick up → Place
- Params: `{name, item, count?, containerSlot?}`

---

### Step 8: `bot_craft` — 合成物品

**FakePlayer 新增：**
```java
public int craftRecipe(CraftingRecipe recipe, int times)
```

**逻辑：**
1. 判断 2x2 还是 3x3：`recipe.canCraftInDimensions(2, 2)`
2. 2x2 → 用 `this.inventoryMenu.getCraftSlots()`
3. 3x3 → 需要先 `bot_activate` 工作台，用 `CraftingMenu.getCraftSlots()`
4. 填充原料 → `recipe.matches()` → `recipe.assemble()` → 消耗原料
5. 返回产物

**BotHandler 命令：**
- Params: `{name, item, count?}`
- 通过 `RecipeManager.getAllRecipesFor(RecipeType.CRAFTING)` 查找配方
- Response: `{crafted, itemName}`
- **同步**命令

---

### Step 9: `bot_interact_entity` — 右键交互实体（村民/动物）

**FakePlayer 新增：**
```java
public InteractionResult interactWithEntity(Entity target, InteractionHand hand)
```
- 调用 `this.interactOn(target, hand)` — Player 继承方法

**BotHandler 命令：**
- Params: `{name, entityId, hand?}`
- Response: `{result, entityId, entityType}`
- **同步**命令

---

## 实现优先级

| 优先级 | 步骤 | 命令 | 复杂度 |
|--------|------|------|--------|
| P0 | Step 1 | bot_activate | 低 ✅ |
| P0 | Step 2 | bot_attack | 中（需完整战斗循环） |
| P0 | Step 3 | bot_consume / bot_use_item | 中（异步） |
| P1 | Step 4 | bot_drop | 低 |
| P1 | Step 5 | bot_look | 低 |
| P1 | Step 6 | bot_equip_item | 中 |
| P2 | Step 7 | container 系列 | 中 |
| P2 | Step 8 | bot_craft | 高 |
| P3 | Step 9 | bot_interact_entity | 低 |

## 技术要点

### 攻击系统参考（Carpet mod + mineflayer-pvp）
- Carpet NMS: `player.attack(target)` → `swing(MAIN_HAND)` → `resetAttackStrengthTicker()`
- resetAttackStrengthTicker() 必须在 attack() 之后调用
- 攻击冷却: `getAttackStrengthScale(0.5f) >= 0.9f` 才打满伤害
- 剑冷却 = 12.5 ticks (1.6 attacks/sec)
- 完整战斗循环: 追击(GoalFollow) + 冷却定时器 + 目标有效性检测 + 冲刺优化
