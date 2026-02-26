# Phase 3C: Advanced Movements (Mining, Placing, Parkour, Pillar, Downward)

## Context

Phase 3B 完成了 16 种移动类型的 A* 寻路 + 完整水体支持。但 bot 只能在已有通道中导航，
遇到方块障碍返回 COST_INF，无法挖穿/搭桥/跑酷。Phase 3C 补全这些能力。

对应 Moves.java 注释: `Phase 3C will add: DOWNWARD, PILLAR, PARKOUR. Omissions: no block placing (bridge/pillar), no mining, no parkour.`

---

## 6 步实现（按依赖顺序）

### Step 1: FakePlayer 动作 API

**目标**: 给 FakePlayer 添加破坏/放置方块、工具选择、物品栏管理能力。

**修改**: `FakePlayer.java`

| 方法 | 说明 |
|------|------|
| `breakBlock(BlockPos)` | 用 `this.gameMode.destroyBlock(pos)` 即时破坏（处理掉落物/耐久/事件） |
| `placeBlock(BlockPos against, Direction face)` | 用 `this.gameMode.useItemOn()` 放置方块 |
| `selectBestTool(BlockState)` | 遍历快捷栏 0-8，选 `stack.getDestroySpeed(state)` 最高的槽位 |
| `hasThrowawayBlock()` / `findThrowawaySlot()` | 检查快捷栏有无可放置的实心方块（排除沙/砾石等 FallingBlock） |
| `equipThrowaway()` | 切换到最佳可放置方块槽位 |
| `equipToMainHand(int slot)` | 切换快捷栏选中槽位 |

**关键 API**:
- `this.gameMode` 是 `ServerPlayerGameMode`（ServerPlayer 的 protected 字段，子类直接访问）
- `destroyBlock(pos)` 处理 BreakEvent + 掉落物 + 工具耐久
- `useItemOn(player, level, stack, hand, hitResult)` 处理放置逻辑
- `stack.getDestroySpeed(state)` 返回工具对方块的挖掘速度

---

### Step 2: 真实 getMiningDurationTicks

**目标**: 替换 MovementHelper 中的 COST_INF stub，让现有 16 种移动能挖穿障碍。

**修改**: `MovementHelper.java`, `CalculationContext.java`, `ActionCosts.java`

**挖掘公式** (Minecraft 1.20.1):
```
hardness = state.getDestroySpeed(level, pos)     // 基础硬度（bedrock = -1）
destroySpeed = player.getDestroySpeed(state)      // 包含工具/附魔/药水/水下/空中
canHarvest = !state.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(state)
divisor = canHarvest ? 30.0 : 100.0
ticks = hardness * divisor / destroySpeed + BREAK_BLOCK_ADDITIONAL_COST
```

**新增 CalculationContext 字段**:
```java
public final @Nullable ServerPlayer miningPlayer;  // 用于 getDestroySpeed() 调用
public final boolean canMine;                       // miningPlayer != null
public final double breakCostMultiplier;            // 默认 1.0
```

**新增 avoidBreaking 守卫**:
```java
static boolean avoidBreaking(CalculationContext ctx, int x, int y, int z, BlockState state) {
    if (isLiquid(state)) return true;
    if (isLiquid(ctx.get(x+1, y, z))) return true;
    if (isLiquid(ctx.get(x-1, y, z))) return true;
    if (isLiquid(ctx.get(x, y+1, z))) return true;
    if (isLiquid(ctx.get(x, y-1, z))) return true;
    if (isLiquid(ctx.get(x, y, z+1))) return true;
    if (isLiquid(ctx.get(x, y, z-1))) return true;
    return false;
}
```

**新增 ActionCosts 常量**:
```java
double BREAK_BLOCK_ADDITIONAL_COST = 4.0;  // 额外惩罚鼓励绕路
double PLACE_BLOCK_COST = WALK_ONE_BLOCK_COST * 4;  // ~18.5 ticks (潜行+放置)
```

---

### Step 3: 放置方块代价 + 桥接/柱子代价

**目标**: 让寻路器能计划"搭桥过缝隙"和"放方块再跳上去"的路径。

**修改**: `MovementHelper.java`, `CalculationContext.java`, `Moves.java`

**新增 CalculationContext 字段**:
```java
public final boolean hasThrowaway;      // 快捷栏有可放置方块
public final double placeBlockCost;     // PLACE_BLOCK_COST
```

**新增 MovementHelper.costOfPlacingAt**:
```java
static double costOfPlacingAt(CalculationContext ctx, int x, int y, int z) {
    if (!ctx.hasThrowaway) return COST_INF;
    BlockState state = ctx.get(x, y, z);
    if (isLiquid(state)) return COST_INF;
    if (!canWalkThrough(ctx, x, y, z, state)) return COST_INF;
    return ctx.placeBlockCost;
}
```

**Moves.java MovementTraverse 桥接分支** (替换 `return COST_INF`):
```java
double placeCost = MovementHelper.costOfPlacingAt(ctx, destX, y-1, destZ);
if (placeCost >= COST_INF) return COST_INF;
if (!MovementHelper.canWalkOn(ctx, x, y-1, z, srcDown)) return COST_INF;
double WC = ActionCosts.SNEAK_ONE_BLOCK_COST;
return WC + placeCost + hardness1 + hardness2;
```

**Moves.java MovementAscend 放置分支** (替换 `return COST_INF`):
```java
if (!MovementHelper.canWalkOn(ctx, destX, y, destZ, destOn)) {
    double placeCost = MovementHelper.costOfPlacingAt(ctx, destX, y, destZ);
    if (placeCost >= COST_INF) return COST_INF;
    if (!MovementHelper.canWalkOn(ctx, destX, y-1, destZ)) return COST_INF;
    walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST) + ctx.jumpPenalty + placeCost;
}
```

---

### Step 4: 执行层 PREPPING 阶段

**目标**: Movement 子类在移动前能破坏/放置方块。

**修改**: `Movement.java`, `MovementTraverse.java`, `MovementAscend.java`

**Movement 基类新增方法**:
```java
protected void faceBlock(BlockPos target)
protected boolean tryBreakBlock(BlockPos pos)
protected boolean tryPlaceBlock(BlockPos target, BlockPos against, Direction face)
```

**即时破坏策略**: 寻路代价已包含挖掘时间，执行层用 `destroyBlock()` 即时破坏。

**MovementTraverse 桥接执行**:
```
检测 dest 下方无地面 →
  bot.setPose(CROUCHING) →
  缓慢靠近边缘 →
  到达边缘时放置方块 →
  恢复 STANDING →
  正常前进
```

---

### Step 5: 新移动类型 (DOWNWARD + PILLAR + PARKOUR)

**修改**: `Moves.java`, `PathExecutor.java`
**新建**: `movement/MovementDownward.java`, `movement/MovementPillar.java`, `movement/MovementParkour.java`

#### 5a. DOWNWARD (向下挖)
- `DOWNWARD(0, -1, 0, false, true)` dynamicY
- 代价: 扫描脚下列，累加 getMiningDurationTicks + FALL_N_BLOCKS_COST
- 执行: 破坏脚下方块 → 重力下落 → 到达 dest

#### 5b. PILLAR (向上搭柱)
- `PILLAR(0, 1, 0)` 固定 Y+1
- 代价: JUMP_ONE_BLOCK_COST + jumpPenalty + placeBlockCost + 头顶清除代价
- 执行: 清除头顶 → 起跳 → Y > src.Y+0.4 时放置 → 落在新方块上

#### 5c. PARKOUR (跑酷跳)
- 4 个方向: PARKOUR_NORTH/SOUTH/EAST/WEST, dynamicXZ (距离 2-4)
- 代价: 必须 canSprint, 路径无障碍, 下方无地面, 着陆可站立
- 执行: 冲刺 → 边缘起跳 → 空中前进 → 落地

---

### Step 6: WebSocket 命令

**修改**: `BotHandler.java`

| 命令 | 参数 | 说明 |
|------|------|------|
| `bot_break` | `{name, x, y, z}` | 破坏指定方块，自动选最佳工具 |
| `bot_place` | `{name, x, y, z, against_x, against_y, against_z, face}` | 放置方块 |
| `bot_equip` | `{name, slot}` 或 `{name, item}` | 切换快捷栏槽位或按物品名搜索 |

---

## 文件总览

```
新建 3 个文件:
  pathfinding/movement/MovementDownward.java
  pathfinding/movement/MovementPillar.java
  pathfinding/movement/MovementParkour.java

修改 8 个文件:
  bot/FakePlayer.java              — Step 1: 6 个动作方法
  pathfinding/ActionCosts.java     — Step 2: BREAK_BLOCK_ADDITIONAL_COST, PLACE_BLOCK_COST
  pathfinding/CalculationContext.java — Step 2+3: miningPlayer, canMine, hasThrowaway, placeBlockCost
  pathfinding/moves/MovementHelper.java — Step 2+3: getMiningDurationTicks, avoidBreaking, costOfPlacingAt
  pathfinding/moves/Moves.java     — Step 3+5: 桥接/柱子代价, DOWNWARD/PILLAR/PARKOUR
  pathfinding/movement/Movement.java — Step 4: faceBlock, tryBreakBlock, tryPlaceBlock
  pathfinding/movement/MovementTraverse.java — Step 4: 桥接执行逻辑
  pathfinding/PathExecutor.java    — Step 5: createMovement 新类型分支
  handler/BotHandler.java          — Step 6: bot_break, bot_place, bot_equip
```

---

## 验证

1. **编译**: `JAVA_HOME=.jdk/jdk-17.0.18+8/Contents/Home ./gradlew build`
2. **挖穿障碍**: bot_goto 到墙壁另一侧，路径应挖穿而非绕路
3. **搭桥过隙**: bot_goto 到 2-3 格宽沟渠对面，bot 应潜行搭桥通过
4. **柱子向上**: bot_goto 到高 3 格的平台，应跳+放方块上去
5. **向下挖**: bot_goto 到脚下 3 格深的洞底，应挖掘脚下方块落下
6. **跑酷跳**: 搭建 2-3 格宽沟渠，bot 应冲刺跳过
7. **液体守卫**: bot 不应挖掉旁边有水/岩浆的方块
8. **WebSocket**: 通过 bot_break/bot_place/bot_equip 直接控制 bot 操作
