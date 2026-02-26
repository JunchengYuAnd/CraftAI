# Future Optimizations

## Pathfinding

### 1. Runtime Tool Change → Path Recalculation
When the bot receives new tools mid-execution (e.g., via `/give`), the current path's cost was calculated with the old hotbar snapshot. The execution layer will use the new tool (faster mining), but the path itself may not be optimal for the new toolset.

**Current behavior:**
- `CalculationContext.hotbarSnapshot` is frozen at path creation time
- `selectBestTool()` uses live inventory at each `startDigging()` call
- Path route doesn't change, only execution speed improves

**Optimization:**
- Detect significant inventory changes (new tool tier) during execution
- Trigger partial or full path recalculation with updated `CalculationContext`
- Balance: avoid excessive recalculations for minor inventory changes (e.g., picking up cobblestone)

## Combat

### 2. Shield Breaking (破盾) — 待测试
`CombatController` 已实现 `shieldBreaking` 功能：检测目标 `isBlocking()` → 自动切斧头攻击 → 5 ticks 后切回剑。代码在 `handleShieldBreak()` / `findAxeSlot()`。

**待验证：**
- 需要对手主动举盾（玩家或自定义 AI 僵尸），普通僵尸不会举盾
- 测试方法：PvP 对真人，对方举盾时观察 bot 是否自动切斧
- 参数：`{"action": "bot_attack_nearby", "params": {"name": "bot", "shieldBreaking": true}}`
- 确保 bot 快捷栏有斧头（`/give bot diamond_axe`）

### 3. Reactionary Crit（反应式暴击）— 待测试
`CombatController` 已实现 `reactionaryCrit` 功能：被打飞到空中时趁下落执行暴击。代码在 `tickReactionaryCrit()`。

**触发条件：**
- `hurtTime == 9 && !onGround()`（被击中且离地）
- 等待 `fallDistance > 0 && vel.y <= -0.25`（过了跳跃顶点）
- 满足冷却 + 距离 + 视线 → 执行暴击

**待验证：**
- 普通僵尸击退力度可能不够让 bot 离地，需要用击退附魔或高处测试
- 参数：`{"action": "bot_attack_nearby", "params": {"name": "bot", "reactionaryCrit": true}}`
- 日志关键字：`REACTIONARY CRIT`

### 4. KB Cancel SHIFT — 待测试
被击中时蹲下 N ticks 减少击退距离。代码在 `tickKBCancel()` SHIFT 分支。

**参数：**`{"action": "bot_attack_nearby", "params": {"name": "bot", "kbCancel": "shift", "kbCancelShiftTicks": 5}}`
**日志关键字：**`KB CANCEL SHIFT`

### 5. Distance Management — ✅ 已测试
已通过测试，bot 保持距离不贴脸。
