# MobProfile 自主学习战斗系统 — 可行性分析与实现计划

## 1. 问题背景

当前战斗系统有 **16+ 个硬编码常量** 假设了特定怪物行为（主要是僵尸）：

| 类别 | 硬编码值 | 假设 |
|------|---------|------|
| `ATTACK_RANGE = 3.0` | bot攻击判定门槛 | 僵尸近战距离 |
| `MELEE_CLOSE = 2.5` | 进入近战状态 | 僵尸追击→近战切换点 |
| `MELEE_EXIT = 4.0` | 退出近战状态 | 僵尸脱离追击距离 |
| `DISENGAGE_CLOSE_RANGE = 3.5` | 触发脱战的"近距离" | 僵尸攻击范围 |
| `DISENGAGE_SAFE_RANGE = 5.0` | 脱战安全距离 | 僵尸追击速度 |
| `optimalDist 动态升级 3.2~4.0` | 多目标距离 | 僵尸 ~2.0 攻击范围 |
| `effectiveThreatK` 乘数 | 威胁权重 | 所有怪物等同 |
| `pursuitDodgeTicks = 5` | 追击闪避时长 | 固定反应时间 |
| `THREAT_MEMORY_TICKS = 60` | 威胁记忆 | 3s通用记忆 |

**核心矛盾**：Minecraft有数百种怪物，mod可以添加更多。我们不可能为每种怪物手动调参。

## 2. 可行性分析

### 2.1 Minecraft 提供了哪些可观测数据？

| 数据源 | API | 可靠性 | 说明 |
|--------|-----|--------|------|
| 被谁打了 | `bot.getLastHurtByMob()` | ★★★★★ | 每次受击可靠触发 |
| 受击时刻 | `bot.hurtTime == 9` | ★★★★★ | 单帧事件，无漏检 |
| 受击伤害量 | `bot.getLastDamageSource().amount` 不存在 | ★☆☆☆☆ | **Minecraft不暴露单次伤害量！** |
| 怪物位置/距离 | `entity.position()` | ★★★★★ | 每tick可读 |
| 怪物速度 | `entity.getDeltaMovement()` | ★★★★ | 1tick延迟 |
| 怪物类型 | `entity.getType().toString()` | ★★★★★ | 稳定标识符 |
| 怪物生命值 | `entity.getHealth()` / `getMaxHealth()` | ★★★★★ | 实时可读 |
| 怪物是否手持弓 | `entity.getMainHandItem()` | ★★★★ | 骷髅等可检测 |
| 弹射物来源 | `AbstractArrow.getOwner()` | ★★★★ | 箭矢追踪射手 |
| 怪物攻击动画 | `entity.attackAnim` | ★★★ | Forge可访问，但时序不精确 |
| bot剩余血量变化 | `bot.getHealth()` 前后差值 | ★★★★ | 间接推算伤害量（需排除其他伤害源） |

### 2.2 可以学到什么？（可行性评估）

| 参数 | 可学性 | 方法 | 置信度 |
|------|--------|------|--------|
| **attackRange** (攻击范围) | ★★★★★ | 记录每次受击时距离，取 P95 | 高 — 只需 5~10 次受击 |
| **attackCooldown** (攻击间隔) | ★★★★ | 记录连续受击间隔tick数，取众数 | 中高 — 需排除多怪同时打 |
| **movementSpeed** (移动速度) | ★★★★ | 每tick记录 deltaMovement 幅度，取平均 | 高 — 大样本容易 |
| **isRanged** (是否远程) | ★★★★★ | 受击距离 > 5.0 连续 3 次 → 远程 | 高 — 二分类，几次就够 |
| **damage** (单次伤害) | ★★★ | bot血量差值（排除同tick多源伤害） | 中 — 有护甲/药水干扰 |
| **threatLevel** (威胁等级) | ★★★★ | attackRange × damage / cooldown | 派生值，取决于上面的精度 |
| **attackAnimation** (攻击前摇) | ★★ | attackAnim 变化到 hurtTime 的延迟 | 低 — 动画时序不可靠 |
| **specialAbility** (特殊能力) | ★ | 苦力怕爆炸、末影人传送等 | 极低 — 每种怪特殊逻辑不同，无法泛化 |

**结论**：前 6 项（attackRange, cooldown, speed, isRanged, damage, threatLevel）完全可行，足以驱动战斗参数自适应。后 2 项不值得投入。

### 2.3 学习需要多少样本？

| 参数 | 收敛样本数 | 实际获取速度 | 可用时间 |
|------|-----------|-------------|---------|
| attackRange | 5~10 次受击 | 被僵尸打：~每20tick一次 | 2~4秒 |
| attackCooldown | 10~15 次受击 | 同上 | 4~6秒 |
| movementSpeed | 20~30 tick采样 | 每tick采样 | 1~2秒 |
| isRanged | 3 次受击 | 被骷髅打：~每40tick一次 | 2~4秒 |
| damage | 5~10 次受击 | 同attackRange | 2~4秒 |

**结论**：与一个怪物战斗 5~10 秒即可收敛所有关键参数。第二次遇到同类型怪物时直接使用已学到的 profile。

### 2.4 关键风险

| 风险 | 严重性 | 缓解方案 |
|------|--------|---------|
| 学习期间被打死 | 高 | 保守默认值（远距离+高闪避），边战斗边学习 |
| 多怪同时攻击导致参数混淆 | 中 | 按 `getLastHurtByMob()` 归因到具体实体类型 |
| Mod怪物的非标准行为 | 中 | profile 有 `confidence` 字段，低置信度时退回默认值 |
| 护甲/附魔影响伤害估算 | 低 | damage 只用于相对排序（threatLevel），不需要绝对精度 |
| 远程+近战混合怪（如凋灵） | 中 | 支持 profile 内记录多种攻击模式 |

## 3. 系统设计

### 3.1 架构总览

```
                     ┌──────────────┐
                     │  MobProfile  │   ← 每种怪物类型一份
                     │  Storage     │   ← JSON持久化
                     └──────┬───────┘
                            │ load/save
                     ┌──────┴───────┐
                     │ MobProfile   │   ← 运行时数据
                     │ Manager      │   ← Map<EntityType, MobProfile>
                     └──────┬───────┘
                            │ getProfile(entity)
              ┌─────────────┼─────────────┐
              │             │             │
     ┌────────┴───┐  ┌─────┴─────┐  ┌───┴──────────┐
     │ Observation │  │ Parameter │  │ Combat       │
     │ Collector   │  │ Adapter   │  │ Controller   │
     │ (每tick)    │  │ (学→用)   │  │ (消费参数)   │
     └─────────────┘  └───────────┘  └──────────────┘
```

### 3.2 MobProfile 数据结构

```java
public class MobProfile {
    // 身份
    public final String entityTypeId;      // e.g. "minecraft:zombie"

    // 学习到的参数
    public double attackRange = 3.0;       // 默认保守值
    public int attackCooldownTicks = 20;   // 默认1秒
    public double movementSpeed = 0.23;    // 默认步行速度
    public double estimatedDamage = 3.0;   // 默认1.5心
    public boolean isRanged = false;
    public double threatLevel = 1.0;       // 派生: range × dmg / cooldown

    // 置信度 (0.0 = 未学习, 1.0 = 高置信)
    public float rangeConfidence = 0.0f;
    public float cooldownConfidence = 0.0f;
    public float speedConfidence = 0.0f;
    public float damageConfidence = 0.0f;
    public float rangedConfidence = 0.0f;

    // 统计数据（用于持续学习）
    public List<Double> hitDistanceSamples = new ArrayList<>();  // 最近N次受击距离
    public List<Integer> hitIntervalSamples = new ArrayList<>(); // 连续受击间隔
    public int totalHitsTaken = 0;
    public int totalEncounters = 0;

    // 持久化时间戳
    public long lastUpdatedTick = 0;
}
```

### 3.3 ObservationCollector（每tick运行）

```
每tick:
  1. 对视野内每个 LivingEntity:
     - 记录 position, deltaMovement → 更新 movementSpeed 滑动平均
     - 检查手持物品 → isRanged 初步判断

  2. 当 bot.hurtTime == 9:
     - attacker = bot.getLastHurtByMob()
     - profile = manager.getProfile(attacker.getType())
     - 记录受击距离 → hitDistanceSamples
     - 记录距上次该类型受击的tick数 → hitIntervalSamples
     - healthBefore - healthAfter → estimatedDamage样本
     - 如果距离 > 5.0 → isRanged票数+1

  3. 每60tick对每个活跃profile:
     - 重新计算 attackRange = percentile(hitDistanceSamples, 95)
     - 重新计算 cooldown = median(hitIntervalSamples)
     - 更新 confidence 基于样本数量
     - 重新计算 threatLevel
```

### 3.4 ParameterAdapter（profile → 战斗参数）

这是最关键的部分 — 把学到的 MobProfile 映射到现有战斗系统参数。

```java
public class ParameterAdapter {

    /** 根据目标的 profile，计算最佳战斗距离 */
    public static double computeOptimalDistance(MobProfile profile, int threatCount) {
        double baseBuffer = 1.0; // 保持在攻击范围外1格
        double safetyMargin = profile.rangeConfidence < 0.5 ? 0.5 : 0.0; // 低置信度额外留距
        double base = profile.attackRange + baseBuffer + safetyMargin;

        // 多目标距离升级（与现有逻辑类似，但基于学到的range）
        double threatBonus = Math.min(threatCount * 0.3, 1.5);
        return base + threatBonus;
    }

    /** 根据 isRanged 决定战斗策略 */
    public static CombatStrategy decideStrategy(MobProfile profile) {
        if (profile.isRanged && profile.rangedConfidence > 0.7) {
            return CombatStrategy.RUSH;      // 远程怪 → 贴脸
        } else {
            return CombatStrategy.KITE;      // 近战怪 → 放风筝
        }
    }

    /** 根据 movementSpeed 调整闪避力度 */
    public static double computeThreatRepulsion(MobProfile profile) {
        // 快速怪物需要更强的排斥力
        double speedRatio = profile.movementSpeed / 0.23; // 相对于僵尸速度
        return 3.0 * speedRatio; // 基础K=3.0，按速度比例缩放
    }

    /** 根据 attackCooldown 调整脱战安全距离 */
    public static double computeDisengageSafeRange(MobProfile profile) {
        // 快攻怪物需要跑更远才安全
        double cooldownFactor = 20.0 / profile.attackCooldownTicks; // 相对于1秒间隔
        return 5.0 * cooldownFactor;
    }

    /** 根据 threatLevel 排序选择优先目标 */
    public static double computeTargetPriority(MobProfile profile, double distance) {
        return profile.threatLevel / (distance + 1.0);
    }
}
```

### 3.5 参数替换映射表

现有硬编码常量如何被 MobProfile 替换：

| 现有常量 | 值 | 替换公式 | 说明 |
|---------|-----|---------|------|
| `ATTACK_RANGE` | 3.0 | `profile.attackRange` | 直接使用学到的范围 |
| `MELEE_CLOSE` | 2.5 | `profile.attackRange - 0.5` | 攻击范围内0.5格进入近战 |
| `MELEE_EXIT` | 4.0 | `profile.attackRange + 1.0` | 攻击范围外1格退出近战 |
| `DISENGAGE_CLOSE_RANGE` | 3.5 | `profile.attackRange + 0.5` | 攻击范围+缓冲 |
| `DISENGAGE_SAFE_RANGE` | 5.0 | `adapter.computeDisengageSafeRange()` | 基于攻击速度 |
| `optimalMeleeDistance` | 3.0 | `adapter.computeOptimalDistance()` | 基于范围+威胁数 |
| 动态升级 3.2~4.0 | 硬编码 | `adapter.computeOptimalDistance(profile, threatCount)` | 自动缩放 |
| `threatRepulsionK` | 3.0 | `adapter.computeThreatRepulsion()` | 基于移动速度 |
| `pursuitDodgeTicks` | 5 | `profile.attackCooldownTicks / 4` | 基于攻击频率 |
| `THREAT_MEMORY_TICKS` | 60 | `profile.attackCooldownTicks * 3` | 基于攻击频率 |
| 目标选择优先级 | 距离最近 | `adapter.computeTargetPriority()` | 基于威胁等级 |
| `effectiveThreatK` 乘数 | 等权 | 按各自 `threatLevel` 加权 | 不同怪物不同权重 |

### 3.6 远程怪物特殊策略

当 `profile.isRanged == true` 且置信度 > 0.7 时：

```
远程怪策略 (RUSH):
  - optimalDistance = 1.5 (贴脸)
  - tangentStrength = 0.8 (强烈左右闪避，躲弹射物)
  - 追击时启用 Z字形接近（每5tick切换strafe方向）
  - 不触发 disengage（远程怪贴脸才安全）
  - 高移速追击（sprint不中断）

近战怪策略 (KITE):
  - optimalDistance = profile.attackRange + 1.0
  - tangentStrength = 0.4 (正常闪避)
  - 正常 disengage 逻辑
  - 正常 KB Cancel / Crit / Wtap
```

### 3.7 持久化

```
config/craftai/mob_profiles/
  ├── minecraft_zombie.json
  ├── minecraft_skeleton.json
  ├── minecraft_spider.json
  ├── twilightforest_naga.json      ← mod怪物
  └── _metadata.json                ← 全局统计
```

- 每 5 分钟或 bot 退出时保存
- JSON 格式，人类可读可编辑
- 启动时加载已有 profiles
- 提供 WebSocket 指令重置/查看 profiles

## 4. 实现步骤

| 阶段 | 内容 | 工作量 | 依赖 |
|------|------|--------|------|
| **A. MobProfile + Manager** | 数据结构、Map管理、JSON读写 | ~150行 | 无 |
| **B. ObservationCollector** | 每tick采集、受击记录、统计计算 | ~200行 | A |
| **C. ParameterAdapter** | profile→参数映射、策略决策 | ~100行 | A |
| **D. CombatController集成** | 替换16个硬编码常量为adapter调用 | ~80行改动 | B, C |
| **E. 远程怪策略** | RUSH模式、Z字形追击、弹射物检测 | ~100行 | C, D |
| **F. 持久化** | JSON保存/加载、WebSocket查看/重置 | ~80行 | A |
| **G. 调试与调优** | 日志、实战测试、参数微调 | 持续 | All |

**总计**：~700行新代码 + ~80行改动现有代码

### 阶段 A: MobProfile + Manager（基础）

```
新文件:
  bot/combat/MobProfile.java        — 数据类
  bot/combat/MobProfileManager.java — 单例管理器

功能:
  - MobProfile 数据结构（如3.2所示）
  - getOrCreateProfile(EntityType) → MobProfile
  - 默认值初始化（保守安全值）
  - profile 置信度计算
```

### 阶段 B: ObservationCollector（学习引擎）

```
新文件:
  bot/combat/ObservationCollector.java

功能:
  - tick(bot, nearbyEntities) — 每tick调用
  - onHit(attacker, distance) — 受击回调
  - 滑动窗口统计（最近50个样本）
  - 置信度更新规则:
    - 0样本 → 0.0
    - 5样本 → 0.5
    - 15样本 → 0.8
    - 30+样本 → 1.0
```

### 阶段 C: ParameterAdapter（学→用桥梁）

```
新文件:
  bot/combat/ParameterAdapter.java

功能:
  - 所有映射函数（如3.4所示）
  - 置信度加权混合:
    result = confidence × learnedValue + (1-confidence) × defaultValue
  - 这确保学习初期使用安全默认值，随着置信度提升逐渐切换到学习值
```

### 阶段 D: CombatController 集成

```
改动 CombatController.java:
  - 构造时注入 MobProfileManager
  - tickMelee/tickPursuing 开头: profile = manager.getProfile(target)
  - 替换 ATTACK_RANGE → adapter.computeAttackRange(profile)
  - 替换 MELEE_CLOSE → profile.attackRange - 0.5
  - 替换 MELEE_EXIT → profile.attackRange + 1.0
  - 替换 DISENGAGE_CLOSE_RANGE → profile.attackRange + 0.5
  - 替换 optimalDist 动态计算 → adapter.computeOptimalDistance()
  - 替换 threatK 乘数 → 按各自 threatLevel 加权
  - scanNearbyThreats 中调用 collector.tick()
  - hurtTime==9 时调用 collector.onHit()
```

### 阶段 E: 远程怪策略

```
改动 CombatController.java + CombatPotentialField.java:
  - 新增 CombatStrategy enum (KITE / RUSH)
  - RUSH模式: 反转PF力方向（pull-in强，push-out弱）
  - Z字形追击: 追击时交替strafe
  - 弹射物检测: 扫描AbstractArrow，根据owner归因到profile
  - 弹射物闪避: 检测来袭箭矢方向，侧向闪避
```

### 阶段 F: 持久化

```
改动 MobProfileManager.java:
  - saveProfiles() → JSON文件
  - loadProfiles() → 启动时读取
  - 定时保存（300tick = 15秒）

新增 WebSocket 指令:
  - bot_mob_profiles → 返回所有已学习的profile
  - bot_reset_profile → 重置指定类型的profile
```

## 5. 学习系统工作流演示

### 第一次遇到僵尸

```
Tick 0:    bot开始攻击僵尸，profile = 默认值 (range=3.0, speed=0.23)
Tick 0-20: ObservationCollector记录僵尸移动速度 → 样本积累
Tick 22:   第一次被打，距离2.1 → hitDistanceSamples=[2.1]
Tick 42:   第二次被打，距离1.9 → hitDistanceSamples=[2.1, 1.9], interval=[20]
Tick 63:   第三次被打，距离2.3 → hitDistanceSamples=[2.1, 1.9, 2.3], interval=[20, 21]
...
Tick 120:  第6次被打 → confidence=0.5，开始混合使用学习值
           attackRange估计: P95(samples) ≈ 2.4
           cooldown估计: median(intervals) ≈ 20
           开始调整: optimalDist = 2.4 + 1.0 = 3.4（比默认3.0更精确）
...
Tick 300:  第15次被打 → confidence=0.8
           attackRange = 2.35 (收敛)
           cooldown = 20 ticks (准确)
           speed = 0.23 (准确)
           所有战斗参数已基于真实数据
```

### 第一次遇到骷髅

```
Tick 0:    profile = 默认值
Tick 15:   被打，距离8.5 → isRanged票数+1
Tick 55:   被打，距离7.2 → isRanged票数+2
Tick 95:   被打，距离9.1 → isRanged票数+3, rangedConfidence=0.7
           → 策略切换: KITE → RUSH
           → optimalDist: 3.0 → 1.5
           → tangentStrength: 0.4 → 0.8
           bot开始冲向骷髅并左右闪避
Tick 130:  贴脸后骷髅切换近战 → 学习近战阶段参数
```

### 遇到 Mod 怪物（暮色森林 Naga）

```
Tick 0:    未知类型 "twilightforest:naga"，profile = 保守默认值
           optimalDist = 3.0 + 0.5(低置信度缓冲) = 3.5
Tick 10:   观测到移动速度 0.35 (比僵尸快50%)
           → threatRepulsionK 自动增大到 4.5
Tick 30:   被打，距离3.8 → 攻击范围可能比僵尸大！
           hitDistanceSamples=[3.8]
Tick 50:   被打，距离4.1 → hitDistanceSamples=[3.8, 4.1]
Tick 100:  confidence=0.5 → attackRange估计 ≈ 4.2
           optimalDist自动调整为 4.2 + 1.0 = 5.2
           bot后退到安全距离放风筝
```

## 6. 局限性（诚实评估）

### 学不到的东西

| 能力 | 原因 | 影响 |
|------|------|------|
| 苦力怕爆炸 | 没有"即将爆炸"的通用检测机制 | 需要硬编码特例 |
| 末影人传送 | 伤害→传送的因果关系无法泛化学习 | 需要硬编码特例 |
| 凋灵双阶段 | 50% HP 行为突变，统计模型无法捕捉 | profile 可记录多阶段，但检测机制需手写 |
| 女巫药水抗性 | 战斗中切换抗性，伤害估算会混乱 | 可容忍，damage 只用于排序 |
| 守卫者激光 | 持续伤害 ≠ 单次受击 | 需要特殊处理持续伤害源 |

### 可以做的缓解

- 维护一个小型 `KnownBehaviors` 注册表，为已知特殊怪物提供 override
- 例如 `"minecraft:creeper" → { keepDistance: 5.0, fleeOnHiss: true }`
- 这不违反自学习原则 — 它是"先验知识"，就像人类知道苦力怕会爆炸

### 性能影响

- 每tick额外计算：遍历视野内实体记录速度 → **O(n), n ≈ 5~20, 忽略不计**
- 受击时统计计算：percentile + median → **每3秒一次，< 0.1ms**
- Profile 内存：每个类型 ~200 bytes，100 种怪 = 20KB → **忽略不计**
- JSON 持久化：每15秒一次，异步写入 → **无感知**

## 7. 与现有系统的兼容性

```
threatAwareness = false (默认)
  → ObservationCollector 仍然运行（被动学习）
  → 但 disengage / 多目标逻辑不变

mobLearning = false (新开关，默认)
  → 完全不创建 MobProfile
  → 所有常量使用硬编码值（行为100%不变）

mobLearning = true
  → 创建 profile，开始学习
  → 低置信度时 blended = 0.8×default + 0.2×learned
  → 高置信度时 blended = 0.2×default + 0.8×learned
  → 永远不完全抛弃默认值（0.95×learned + 0.05×default上限）
```

## 8. 总结

| 维度 | 评估 |
|------|------|
| **可行性** | ★★★★☆ — 核心参数（range, cooldown, speed, isRanged）完全可行 |
| **实用性** | ★★★★★ — 解决了最大痛点：不同怪物需要不同距离和策略 |
| **工作量** | ~700 行新代码 + 80 行改动 |
| **风险** | 低 — 默认关闭，有置信度混合保底 |
| **对 mod 怪物的适配力** | ★★★★ — 自动学习 range/speed/cooldown，几秒内收敛 |
| **局限** | 特殊行为（爆炸/传送/多阶段）仍需硬编码 |

**建议**：值得实现。先做 A~D（核心学习+集成），再做 E（远程策略），最后做 F（持久化）。
