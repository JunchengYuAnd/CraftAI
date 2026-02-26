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
