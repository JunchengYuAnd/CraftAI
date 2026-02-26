# Combat Module Status

## Architecture

**CombatController** — 状态机驱动的近战系统，移植自 mineflayer-custom-pvp SwordPvp。

```
State Machine: IDLE → PURSUING → MELEE → PURSUING → ...
                                   ↓
                          tickMelee() pipeline:
                          1. KB Cancel
                          2. Shield Breaking
                          3. Strafe
                          4. Reactionary Crit
                          5. Backoff on Hit
                          6. Distance Management
                          7. Attack Mode Dispatch
                          8. Auto Shield
```

**文件：**
- `bot/CombatConfig.java` — 配置 POJO（攻击模式/闪避/盾牌/KB Cancel 等）
- `bot/CombatController.java` — 核心状态机 + 所有战斗技巧
- `bot/FakePlayer.java` — 攻击力覆写、属性刷新、副手盾牌、手动 fallDistance
- `handler/BotHandler.java` — WebSocket 命令解析（`bot_attack` / `bot_attack_nearby`）

## 已实现功能

### 攻击模式（AttackMode）

| 模式 | 状态 | 说明 |
|------|------|------|
| NORMAL | ✅ 已测试 | 冲刺接近 + 冷却满攻击 |
| CRIT | ✅ 已测试 | 跳跃暴击子状态机（IDLE→JUMPING→FALLING→ATTACK_READY） |
| WTAP | ✅ 已测试 | 冲刺重置击退（每刀 +1 knockback） |
| STAP | ✅ 已测试 | 后退式冲刺重置（APPROACH→BACKING→RE_ENGAGE），状态机循环正常 |

### 闪避模式（StrafeMode）

| 模式 | 状态 | 说明 |
|------|------|------|
| NONE | ✅ | 直线走向目标 |
| CIRCLE | ✅ 已测试 | 固定方向走位，周期换向 |
| RANDOM | ✅ 已实现 | 随机方向切换 |
| INTELLIGENT | ✅ 已实现 | 被击中反向 + 周期随机 |

### 盾牌系统

| 功能 | 状态 | 说明 |
|------|------|------|
| Auto Shield | ✅ 已测试 | 攻击间隙举盾（副手自动装备） |
| Shield Breaking | ✅ 已实现 | 检测 `isBlocking()` → 切斧攻击 → 切回剑。待真人测试 |

### 新增战斗增强（Step 2C）

| 功能 | 状态 | 说明 |
|------|------|------|
| KB Cancel JUMP | ✅ 已测试 | 被击中时 sprint+jump 抵消击退 |
| KB Cancel SHIFT | ✅ 已实现 | 被击中时 sneak N ticks 减少击退。待测试 |
| Reactionary Crit | ✅ 已实现 | 被打飞时趁下落暴击。待测试 |
| Distance Mgmt | ✅ 已测试 | tooCloseRange + backoffOnHitTicks |

### 其他

| 功能 | 状态 | 说明 |
|------|------|------|
| bot_attack_nearby | ✅ 已测试 | 自动扫描攻击范围内敌对生物，连续击杀 |
| 自研冷却计时器 | ✅ | 绕过 ServerPlayer.tick() NPE 导致的 attackStrengthTicker 不增问题 |
| 手动属性刷新 | ✅ | refreshMainHandAttributes() 替代无法运行的 collectEquipmentChanges() |
| 手动 fallDistance | ✅ | FakePlayer.tick() 中用 `getY() - yo` 计算，绕过 checkFallDamage() 跳过问题 |

## WebSocket API

### bot_attack
```json
{
  "action": "bot_attack",
  "params": {
    "name": "bot",
    "entityId": 42,
    "mode": "normal|crit|wtap|stap",
    "strafe": "none|circle|random|intelligent",
    "strafeIntensity": 0.8,
    "strafeChangeInterval": 40,
    "shieldBreaking": false,
    "autoShield": false,
    "kbCancel": "none|jump|shift",
    "kbCancelShiftTicks": 5,
    "reactionaryCrit": false,
    "tooCloseRange": 0.0,
    "backoffOnHitTicks": 0
  }
}
```

### bot_attack_nearby
```json
{
  "action": "bot_attack_nearby",
  "params": {
    "name": "bot",
    "radius": 32,
    "...同上所有战斗参数..."
  }
}
```

所有参数可选，不传则保持默认行为（向后兼容）。

## 未实现 — 对比 mineflayer-custom-pvp

### 高优先级

| 功能 | 说明 |
|------|------|
| 弓箭远程攻击 | BowPvp 模块：弹道模拟 + 瞄准 + 蓄力释放 |
| 移动目标预判 | 用 getDeltaMovement() 外推目标位置再瞄准 |
| Predictive Following | 追击时预测目标未来位置 |

### 中优先级

| 功能 | 说明 |
|------|------|
| AABB 距离计算 | 用碰撞箱最近点替代中心距离 |
| 双斧破盾 | 第一斧禁盾 + 第二斧造伤 |
| Strafe 角度检查 | 仅在目标面朝自己时闪避 |
| 弩/投掷物 | 弩两阶段射击、雪球/末影珍珠/药水 |

### 低优先级

| 功能 | 说明 |
|------|------|
| Miss Chance | 人类化失误率 |
| 冷却随机偏移 | 攻击间隔加随机 ±N ticks |
| Packet Crit / Short Hop | 客户端向暴击技巧（服务端可能不适用） |
| 1.8 PvP CPS | 旧版战斗系统点击速率模拟 |
| Smooth Look | 非线性旋转插值 |

## Git History

| Commit | 内容 |
|--------|------|
| `c67ed69` | Phase 4 Step 2: PvP 核心 + 暴击/W-tap/闪避/盾牌 |
| (pending) | Phase 4 Step 2C: KB Cancel / Reactionary Crit / S-tap / 距离管理 |
