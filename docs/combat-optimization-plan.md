# Combat System Optimization Plan

## Current Status (v1)

**Already implemented:**
- 4 attack modes: normal / crit / wtap / stap
- 4 strafe modes: none / circle / random / intelligent
- KB Cancel: jump / shift
- Shield breaking (axe switch) + auto-shield
- Threat awareness: scan nearby threats, evasion during pursuit
- Auto-attack: target switching, finishing blow mode
- Distance management: tooCloseRange, backoff, swarm retreat

**Key gaps identified:**
- No food/healing in combat
- No retreat/flee decision
- Movement system is discrete (if/else), not continuous
- Target selection is distance-only, no threat scoring
- No sweep attack vs focus-fire awareness
- No wall/cliff/hazard avoidance
- No terrain-aware tactical positioning
- No armor/enchantment awareness

---

## Architecture Overview

The optimization replaces the current discrete if/else combat decisions with three layered systems:

```
┌─────────────────────────────────────────────┐
│  Layer 3: Utility AI (Decision Layer)       │
│  "What should I do this tick?"              │
│  Score: ATTACK / RETREAT / EAT / SHIELD     │
├─────────────────────────────────────────────┤
│  Layer 2: Potential Field (Movement Layer)  │
│  "Where should I move?"                     │
│  Forces: target ring + threat repulsion     │
│          + wall/cliff/hazard avoidance      │
├─────────────────────────────────────────────┤
│  Layer 1: Combat Primitives (Action Layer)  │
│  "How do I execute this action?"            │
│  Existing: wtap, stap, crit, shield, KB     │
│  New: eat, flee, sweep, kite                │
└─────────────────────────────────────────────┘
```

Each layer is independently testable. Lower layers work without upper layers (backwards compatible). Upper layers enhance but don't replace existing config-driven behavior.

---

## Phase 1: Potential Field Movement System

**Goal:** Replace `computeStrafeValue()` + `computeForward()` with a continuous 2D vector field that naturally handles multiple threats, walls, and hazards.

**Why first:** This is the single highest-impact change. The current system only considers the nearest threat and uses discrete if/else branches. A potential field naturally combines ALL threats into one optimal movement direction.

### 1A. Core Potential Field Engine

**New file:** `bot/combat/CombatPotentialField.java`

The field computes a 2D force vector (world XZ plane) by summing:

| Source | Type | Formula | Purpose |
|--------|------|---------|---------|
| Primary target | Attractive ring | `F = k * (dist - optimalDist)` | Maintain optimal melee distance (2.5 blocks) |
| Primary target | Tangential | `F = perpendicular × 0.4` | Create orbital strafing motion |
| Secondary threats | Repulsive | `F = k / dist²` (inverse-square) | Push away from nearby hostiles |
| Solid blocks | Repulsive | Cardinal direction check × 0.5 | Don't get pushed into walls |
| Cliff edges | Repulsive | 3-block drop check × 1.5 | Don't fall off ledges |
| Lava | Strong repulsive | `F = 5.0 / dist²` | Avoid death hazards |
| Water | Moderate repulsive | `F = 1.0 / dist²` | Avoid slow zones |

**Coordinate conversion:** World-space force → bot-relative `(forward, strafe)` via yaw projection:
```
forward = dot(force, botForwardDir)
strafe  = -dot(force, botRightDir)
```

**Key parameters (tunable via CombatConfig):**
```java
public double optimalMeleeDistance = 2.5;   // ring field center
public double tangentStrength = 0.4;         // orbital strafe force
public double threatRepulsionK = 3.0;        // threat push strength
public double threatRepulsionRange = 6.0;    // ignore threats beyond this
```

### 1B. Wall & Terrain Awareness

Sample 4 cardinal directions at 1.5 blocks distance:
- Solid block → repulsive force away
- Air below (2+ blocks) → cliff repulsion (stronger force)

Sample 8 surrounding positions for lava/water at 2 blocks:
- Lava → very strong repulsion (5x)
- Water → moderate repulsion (1x)

### 1C. Integration with CombatController

**Activation:** When `config.usePotentialFields = true` (new config flag).

Replace in `tickMelee()`:
```java
// OLD:
float strafeValue = computeStrafeValue();
float forward = computeForward(dist, strafeValue);
bot.setMovementInput(forward, strafeValue, jump);

// NEW (when potentialFields enabled):
double[] force = CombatPotentialField.computeMovementVector(
    bot, target, nearbyThreats, config.optimalMeleeDistance);
float[] inputs = CombatPotentialField.worldToRelativeInput(
    force[0], force[1], bot.getYRot());
bot.setMovementInput(inputs[0], inputs[1], jump);
bot.setSprinting(inputs[0] > 0.5f);
```

**Backwards compatibility:** `usePotentialFields = false` (default) keeps existing discrete strafe/forward.

### 1D. Pursuit Evasion Upgrade

Replace `tickPursuing()` proactive evasion block with potential field:
- Target: attractive (strong pull, we want to reach it)
- Threats: repulsive (but weaker than in melee — we still need to approach)
- Result: Bot navigates AROUND threats toward target, not through them

### Implementation steps:
1. Create `CombatPotentialField.java` with `computeMovementVector()` + `worldToRelativeInput()`
2. Add wall/cliff/hazard sampling
3. Add `usePotentialFields` to CombatConfig + BotHandler parsing
4. Wire into `tickMelee()` as alternative to discrete strafe/forward
5. Wire into `tickPursuing()` proactive evasion
6. Test: single target orbit, multi-threat evasion, wall avoidance, cliff edge

---

## Phase 2: Survival System (Retreat + Food)

**Goal:** Bot can disengage when losing, eat food to recover, and re-engage.

### 2A. Retreat Decision Scoring

Every tick, compute a retreat urgency score (0.0 ~ 1.0):

| Factor | Formula | Weight | Notes |
|--------|---------|--------|-------|
| Low HP | `(0.30 - myHpPct) × 3.0` when HP < 30% | High | Primary retreat trigger |
| HP disadvantage | `(targetHpPct - myHpPct) × 0.5` when losing | Medium | Enemy is healthier |
| Outnumbered | `0.15 × threatCount` | Medium | More threats = more danger |
| No food + hurt | `+0.2` when no food and HP < 50% | Medium | Can't heal = must flee |
| No weapon | `+0.3` when weapon damage ≤ 1 | High | Fist fighting is suicide |
| Counter: finish kill | `-0.3` when target HP < 15% | Negative | Stay to finish the kill |

**Hysteresis:** Once retreating, require score < 0.3 to re-engage (prevents oscillation).

**Threshold:** Score > 0.7 → enter RETREAT state.

### 2B. New Combat States

Add two states to `CombatController.State`:

```
IDLE → PURSUING → MELEE → RETREATING → KITING → MELEE/IDLE
                  ↑────────────────────────↓
```

**RETREATING:**
- Sprint away from all enemies (inverted potential field: all enemies repulsive)
- If safe distance (6+ blocks) and have food → enter KITING
- If safe distance and no food → continue fleeing
- If no enemies in 16 blocks → stop combat

**KITING (hit-and-run):**
- Sprint away from enemies while maintaining spacing
- When attack cooldown ready AND target in range → turn, attack, turn back
- When HP > 80% → re-enter PURSUING (re-engage)
- When food available and 6+ block gap → eat food

### 2C. Food Eating

**New FakePlayer method:** `bot.eatFood()`

Implementation:
1. Find food item in hotbar (search by `isEdible()`)
2. Equip food to main hand (save previous slot)
3. Call `bot.startUsingItem(MAIN_HAND)` — starts eating animation
4. Wait 32 ticks (1.6 seconds, vanilla eat duration)
5. Food is consumed, restore previous weapon slot

**Integration with combat:**
- Only eat during RETREATING/KITING when distance > 6 blocks
- Abort eating if enemy closes within 3 blocks
- Track eating state: `isEating`, `eatTicksRemaining`

### 2D. Config

```java
// Retreat
public boolean autoRetreat = false;           // enable retreat system
public float retreatHpThreshold = 0.25f;      // start considering retreat
public float reEngageHpThreshold = 0.80f;     // re-engage when HP recovered

// Food
public boolean autoEat = false;               // eat food during retreat
public int eatSafeDistance = 6;               // min distance to start eating
```

### Implementation steps:
1. Add `RETREATING`, `KITING` states to State enum
2. Implement retreat urgency scoring in `evaluateRetreatUrgency()`
3. Implement `tickRetreating()` — inverted potential field movement
4. Implement `tickKiting()` — hit-and-run with turn-attack
5. Add `eatFood()` to FakePlayer + eating state tracking
6. Integrate eating into KITING/RETREATING states
7. Add config fields + BotHandler parsing
8. Test: HP-based retreat trigger, food eating, re-engagement, hysteresis

---

## Phase 3: Smart Target Selection

**Goal:** Replace distance-only target selection with multi-factor threat scoring.

### 3A. Threat Priority Scoring

Score each hostile entity to determine attack priority:

| Factor | Formula | Weight | Notes |
|--------|---------|--------|-------|
| Distance | `max(0, 1.0 - dist/10) × 3.0` | High | Closer = higher priority |
| Low HP (finishable) | `(0.3 - hpPct) × 8.0` when HP < 30% and dist < 4 | High | Snowball advantage |
| Hitting us | `+2.0` if `lastHurtByMob == this` | High | Fight back priority |
| Entity type danger | Creeper +3, Skeleton +1.5, Witch +2 | Medium | Creeper = immediate threat |
| Current target bonus | `+1.0` if already our target | Medium | Prevent oscillation |
| Swarmed finish | `+2.0` when 3+ threats and HP < 50% and dist < 3 | High | Must reduce mob count |

**Selection:** Attack the entity with highest score. Only switch when new target scores > current + 1.5 (hysteresis).

### 3B. Focus-Fire vs Sweep Decision

Minecraft sweep attack (1.9+) hits all entities within 1 block of primary target when:
- Attack strength ≥ 90%
- NOT sprinting
- Walk speed below sprint threshold
- Target on ground

**Decision logic each tick:**
```
if (clustered enemies within 2 blocks ≥ 2):
    → SWEEP mode: don't sprint, standing attack for AoE
else:
    → FOCUS mode: W-tap/S-tap for single-target KB
```

**Integration:** New AttackStrategy that overrides sprint behavior. When SWEEP active, disable W-tap sprint reset and use standing attacks.

### 3C. Config

```java
public boolean smartTargeting = false;    // utility-based target selection
public boolean allowSweepAoE = true;      // use sweep when enemies clustered
```

### Implementation steps:
1. Create `scoreThreatPriority()` method
2. Replace `findAbsoluteClosestHostile()` with scoring-based selection (when smartTargeting enabled)
3. Add sweep detection: count enemies within 2 blocks of target
4. When sweep → disable sprint, use normal attack mode
5. Add config + parsing
6. Test: target priority (creeper > zombie), finish low-HP, sweep clusters

---

## Phase 4: Tactical Positioning

**Goal:** Use terrain for tactical advantage.

### 4A. Defensive Position Finding

When outnumbered (3+ threats), find a position that limits attack angles:

Score positions within 5 blocks:
- Count adjacent solid blocks at bot Y level (N/S/E/W)
- 2 walls (corner) = ideal, score +4
- 3 walls (dead end) = good but risky, score +3
- Must be standable (air at feet + head, solid below)

**Integration:** When swarm detected, pathfind to best defensive position before fighting.

### 4B. Height Advantage

Check if 1-block-high ledge is nearby:
- Bot on higher ground → +KB, harder for mobs to reach
- Factor into defensive position scoring

### 4C. Emergency Pillar

When HP critical (< 10%) and have blocks:
- Build 1x1 pillar (look down, jump + place block)
- Gain height to eat food safely
- Gate behind `allowPillarEscape` config

### Implementation steps:
1. `findDefensivePosition()` — position scoring with wall counting
2. Integrate into swarm retreat: pathfind to corner before standing ground
3. Height advantage detection (1-block ledge bonus)
4. Emergency pillar escape (config gated)
5. Test: corner backing, height seeking, pillar escape

---

## Phase 5: Utility AI Decision Layer

**Goal:** Replace hardcoded attack mode switching with dynamic per-tick action scoring.

### 5A. Combat Snapshot

Each tick, capture the full combat state:
```java
class CombatSnapshot {
    float myHpPercent;
    float myFoodLevel;
    float targetHpPercent;
    double distToTarget;
    boolean targetBlocking;
    int nearbyThreatCount;
    long closeThreatCount;     // within 3 blocks
    boolean haveFoodInInventory;
    boolean haveShieldEquipped;
    float attackCooldownPercent;
    boolean onGround;
    boolean isBeingHit;        // hurtTime > 0
    int ticksInCurrentState;
    float retreatUrgency;
}
```

### 5B. Action Scoring

Each possible action gets scored 0.0 ~ 1.0:

| Action | Key Considerations | Notes |
|--------|-------------------|-------|
| ATTACK_NORMAL | cooldown ready × in range × target not blocking | Baseline |
| ATTACK_CRIT | cooldown ready × in range × on ground × target HP > 50% | Worth the setup time |
| ATTACK_WTAP | cooldown ready × in range × target not against wall | KB is useful |
| ATTACK_STAP | cooldown ready × in range × 1v1 situation | Spacing matters |
| ATTACK_SWEEP | clustered enemies × cooldown ready × not sprinting | AoE damage |
| SHIELD_RAISE | have shield × target in range × cooldown > 5 ticks | Defend between attacks |
| SHIELD_BREAK | target blocking × have axe × shield break off cooldown | Remove defense |
| EAT_FOOD | HP < 50% × have food × safe distance | Heal up |
| RETREAT | retreatUrgency > 0.7 | Disengage |
| KB_CANCEL | just hit × enemy attack | Counter knockback |

**Selection:** Highest-scoring action wins. Ties broken by priority order.

### 5C. Config

```java
public boolean utilityAI = false;          // enable utility decision layer
// When enabled, attackMode becomes a preference/bias, not a hard selection
```

### Implementation steps:
1. Create `CombatSnapshot` data class
2. Create `CombatUtilityEvaluator` with `selectBestAction(snapshot)`
3. Define utility curves for each action (linear, sigmoid, inverse)
4. Wire into `tickMelee()` as alternative to `switch(config.attackMode)`
5. Allow `config.attackMode` to bias scoring (+0.1 to preferred mode)
6. Test: dynamic mode switching, retreat trigger, shield timing

---

## Phase 6: Polish & Advanced

### 6A. Shield Timing Optimization

Current: Raise when `ticksUntilAttack > 3`, lower at `≤ 3`
Problem: Shield needs 5 ticks to activate

Fix: Raise when `ticksUntilAttack > 8`, shield activates at tick 5, lower at tick 3.
For sword (13 tick cycle): active shield window = ticks 5-10 (5 ticks of protection).

### 6B. Armor & Enchantment Awareness

Read target's armor/enchantments to estimate:
- Damage reduction → affects DPS calculation
- Presence of Thorns → factor into attack frequency
- Protection level → affects retreat decision (target is tanky)

### 6C. Bow/Ranged Support

New state: RANGED
- When target > 6 blocks and bot has bow
- Draw bow, predict target position, release
- Switch to melee when target closes in

### 6D. Status Effect Handling

React to debuffs:
- Poison/Wither → increase retreat urgency
- Slowness → increase threat evasion weight
- Blindness → reduce scan radius
- Weakness → prefer shield over attack

---

## Implementation Priority & Dependencies

```
Phase 1 (Potential Fields)     ← No dependencies, highest impact
    ↓
Phase 2 (Survival)             ← Uses potential fields for retreat movement
    ↓
Phase 3 (Smart Targeting)      ← Independent, can be parallel with Phase 2
    ↓
Phase 4 (Tactical Position)    ← Uses potential fields + terrain queries
    ↓
Phase 5 (Utility AI)           ← Wires all previous phases together
    ↓
Phase 6 (Polish)               ← Independent improvements
```

**Recommended order:** 1 → 2 → 3 → 4 → 5 → 6

Each phase is self-contained with its own config flags. Enabling one doesn't require enabling others. Phase 5 (Utility AI) is the capstone that dynamically orchestrates all previous phases.

---

## New Files

| File | Purpose |
|------|---------|
| `bot/combat/CombatPotentialField.java` | Potential field computation + coordinate conversion |
| `bot/combat/CombatUtilityEvaluator.java` | Utility AI action scoring |
| `bot/combat/CombatSnapshot.java` | Per-tick state snapshot |
| `bot/combat/ThreatPrioritizer.java` | Multi-factor target scoring |
| `bot/combat/RetreatDecision.java` | Retreat urgency evaluation |

## Modified Files

| File | Changes |
|------|---------|
| `bot/CombatController.java` | New states (RETREATING, KITING), potential field integration, utility AI hook |
| `bot/CombatConfig.java` | New config fields for each phase |
| `bot/FakePlayer.java` | `eatFood()` method, eating state tracking |
| `handler/BotHandler.java` | Parse new config fields |

---

## Testing Commands

### Phase 1: Potential Field
```json
{"action":"bot_attack_nearby","params":{"name":"bot","usePotentialFields":true,"threatAwareness":true,"threatScanRadius":8}}
```

### Phase 2: Survival
```json
{"action":"bot_attack_nearby","params":{"name":"bot","autoRetreat":true,"autoEat":true,"retreatHpThreshold":0.25,"usePotentialFields":true,"threatAwareness":true}}
```

### Phase 3: Smart Targeting
```json
{"action":"bot_attack_nearby","params":{"name":"bot","smartTargeting":true,"allowSweepAoE":true,"threatAwareness":true}}
```

### Phase 5: Full Utility AI
```json
{"action":"bot_attack_nearby","params":{"name":"bot","utilityAI":true,"usePotentialFields":true,"autoRetreat":true,"autoEat":true,"smartTargeting":true,"allowSweepAoE":true,"threatAwareness":true,"threatScanRadius":8}}
```

---

## Research Sources

- [mineflayer-pvp](https://github.com/PrismarineJS/mineflayer-pvp) — Basic PvE/PvP: pathfinder follow + cooldown attack, shield for creepers only
- [mineflayer-custom-pvp](https://github.com/nxg-org/mineflayer-custom-pvp) — Advanced: strafing (circle/random/intelligent), shield break (axe swap, 3-tick wait), crits (packet/hop/reaction), W-tap/S-tap, KB cancel (velocity/jump/shift), distance backoff
- [PracticeBotPvP](https://www.spigotmc.org/resources/practicebotpvp.130722/) — Tick-perfect W-tap (tick 0 attack+sprint, tick 1 stop sprint, tick 3 ready), crit jump (0.42 Y vel, 7 ticks to fall), difficulty tiers (crit%/combo%/strafe%/reaction speed), evasive dodging at low HP
- [Utility AI Introduction](https://shaggydev.com/2023/04/19/utility-ai/) — Score 0-1 per action, multiply considerations, sigmoid curves for urgency, bucket scoring
- [Enemy AI for Melee Combat](https://www.gamedeveloper.com/design/enemy-design-and-enemy-ai-for-melee-combat-systems) — Tell systems, attack tokens, enemy categorization (fodder/elite/boss)
- Artificial Potential Fields (Khatib 1986) — Attractive/repulsive fields, adapted for multi-agent game AI
- Minecraft Wiki: Combat — Cooldown formulas, crit conditions, sweep mechanics, shield 5-tick activation
