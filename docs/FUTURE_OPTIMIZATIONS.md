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
