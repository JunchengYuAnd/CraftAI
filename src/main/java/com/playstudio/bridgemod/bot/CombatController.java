package com.playstudio.bridgemod.bot;

import com.playstudio.bridgemod.BridgeMod;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * Controls melee combat for a FakePlayer.
 * Ported from mineflayer-custom-pvp SwordPvp core loop.
 *
 * State machine: IDLE → PURSUING → MELEE → PURSUING → ...
 *
 * PURSUING: delegates to BotController.startGoto() for A* pathfinding.
 * MELEE: direct movement control (walk forward + sprint + attack).
 *
 * Advanced techniques (configurable via CombatConfig):
 * - CRIT: jump + fall + attack for 1.5x damage
 * - WTAP: sprint reset between attacks for max knockback
 * - STRAFE: circle/random/intelligent dodging
 * - SHIELD: auto-axe vs blocking targets, auto-raise own shield
 *
 * Ticked from BotHandler.onServerTick(), before BotController.tick().
 */
public class CombatController {

    public enum State {
        IDLE,
        PURSUING,   // pathfinding to target via BotController
        MELEE       // in attack range, direct movement + attacking
    }

    // Crit sub-state machine
    private enum CritPhase {
        IDLE,           // waiting for cooldown to approach
        JUMPING,        // jump initiated, waiting to leave ground
        FALLING,        // airborne, waiting for fallDistance > 0
        ATTACK_READY    // falling with cooldown ready, execute crit
    }

    private final FakePlayer bot;
    private final BotController navController;

    // Combat state
    private State state = State.IDLE;
    private int targetEntityId = -1;
    private LivingEntity target = null;
    private BiConsumer<Boolean, String> callback = null;

    // Configuration (set per-fight via startAttack)
    private CombatConfig config = new CombatConfig();

    // Pursuit re-pathfind tracking
    private double lastTargetX, lastTargetY, lastTargetZ;
    private int ticksSinceRepath = 0;

    // Own attack cooldown tracker (vanilla attackStrengthTicker doesn't increment
    // because ServerPlayer.tick() NPEs before reaching Player.tick())
    private int ticksSinceLastAttack = 100; // start high = ready immediately

    // Crit state
    private CritPhase critPhase = CritPhase.IDLE;

    // Strafe state
    private float currentStrafeDirection = 1.0f; // +1 = left, -1 = right
    private int ticksSinceStrafeChange = 0;

    // Shield handling
    private int previousWeaponSlot = -1;    // slot to return to after axe attack
    private int shieldBreakCooldown = 0;    // ticks remaining before switching back
    private boolean isShielding = false;    // whether bot is currently blocking

    // KB Cancel state
    private boolean kbCancelActive = false;
    private int kbCancelTicksRemaining = 0;

    // Reactionary Crit state
    private boolean reactionaryCritActive = false;
    private int reactionaryCritTicks = 0;

    // S-tap state
    private enum StapPhase { APPROACH, BACKING, RE_ENGAGE }
    private StapPhase stapPhase = StapPhase.APPROACH;
    private int stapBackTicksRemaining = 0;

    // Distance management state
    private int backoffTicksRemaining = 0;

    // Auto-attack nearby hostiles mode
    private boolean autoAttackMode = false;
    private double autoAttackRadius = 32.0;
    private BiConsumer<Boolean, String> autoAttackCallback = null;
    private int killCount = 0;

    // Constants
    private static final double ATTACK_RANGE = 3.0;       // survival melee reach
    private static final double MELEE_CLOSE = 2.5;        // switch to melee state
    private static final double MELEE_EXIT = 4.0;         // switch back to pursuing
    private static final double VIEW_DISTANCE = 48.0;     // give up distance
    private static final double REPATH_DIST_SQ = 4.0;     // re-pathfind when target moves 2+ blocks
    private static final int REPATH_INTERVAL = 20;         // re-pathfind at most every 20 ticks
    private static final int PURSUIT_RANGE = 2;            // GoalNear range for pathfinding

    public CombatController(FakePlayer bot, BotController navController) {
        this.bot = bot;
        this.navController = navController;
    }

    /**
     * Start attacking a target entity with given combat config.
     * Callback fires when combat ends: (success=target died, reason).
     */
    public void startAttack(int entityId, CombatConfig config, BiConsumer<Boolean, String> cb) {
        // Cancel any existing combat
        if (state != State.IDLE) {
            stopCombat("replaced");
        }

        Entity entity = bot.serverLevel().getEntity(entityId);
        if (entity == null) {
            cb.accept(false, "entity_not_found");
            return;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            cb.accept(false, "invalid_target");
            return;
        }

        this.targetEntityId = entityId;
        this.target = living;
        this.callback = cb;
        this.config = config != null ? config : new CombatConfig();
        this.ticksSinceRepath = 0;

        // Auto-select best weapon
        bot.selectBestWeapon();

        // Auto-equip shield to offhand if autoShield is enabled
        if (this.config.autoShield) {
            bot.equipShieldToOffhand();
        }

        BridgeMod.LOGGER.info("Bot '{}' starting combat with entity {} ({}) [mode={}, strafe={}]",
                bot.getBotName(), entityId, target.getType().toShortString(),
                this.config.attackMode, this.config.strafeMode);

        // Check if already in melee range
        double dist = bot.distanceTo(target);
        if (dist <= MELEE_CLOSE) {
            enterMelee();
        } else {
            startPursuit();
        }
    }

    /** Backward-compatible overload (default config). */
    public void startAttack(int entityId, BiConsumer<Boolean, String> cb) {
        startAttack(entityId, new CombatConfig(), cb);
    }

    /**
     * Start auto-attack mode: continuously scan for and attack nearby hostile mobs.
     * When one target dies, automatically find the next closest hostile.
     * Callback fires when manually cancelled or no hostiles remain.
     */
    public void startAutoAttack(CombatConfig config, double radius, BiConsumer<Boolean, String> cb) {
        // Cancel any existing combat
        if (state != State.IDLE) {
            stopCombat("replaced");
        }

        this.autoAttackMode = true;
        this.autoAttackRadius = radius;
        this.autoAttackCallback = cb;
        this.config = config != null ? config : new CombatConfig();
        this.killCount = 0;

        BridgeMod.LOGGER.info("Bot '{}' auto-attack mode ON (radius={}, mode={})",
                bot.getBotName(), (int) radius, this.config.attackMode);

        // Find first target
        if (!findAndAttackNextHostile()) {
            autoAttackMode = false;
            cb.accept(true, "no_hostiles");
        }
    }

    /**
     * Scan for nearest hostile entity within radius and start attacking it.
     * Returns true if a hostile was found.
     */
    private boolean findAndAttackNextHostile() {
        LivingEntity nearest = null;
        double nearestDist = autoAttackRadius;

        for (Entity entity : bot.serverLevel().getAllEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!(entity instanceof Enemy)) continue; // only hostile mobs
            if (!living.isAlive()) continue;
            if (entity == bot) continue;

            double dist = bot.distanceTo(entity);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = living;
            }
        }

        if (nearest == null) {
            return false;
        }

        // Start attacking this target (internal — no callback, we handle it in stopCombat)
        this.targetEntityId = nearest.getId();
        this.target = nearest;
        this.callback = null; // auto-attack handles its own lifecycle
        this.ticksSinceRepath = 0;

        bot.selectBestWeapon();
        if (config.autoShield) {
            bot.equipShieldToOffhand();
        }

        BridgeMod.LOGGER.info("Bot '{}' auto-attack: targeting {} ({}, dist={})",
                bot.getBotName(), nearest.getId(), nearest.getType().toShortString(),
                String.format("%.1f", nearestDist));

        double dist = bot.distanceTo(nearest);
        if (dist <= MELEE_CLOSE) {
            enterMelee();
        } else {
            startPursuit();
        }
        return true;
    }

    /**
     * Stop combat externally (bot_attack_cancel, bot_stop, bot_despawn).
     */
    public void stop() {
        if (state != State.IDLE || autoAttackMode) {
            stopCombat("cancelled");
        }
    }

    /**
     * Tick the combat loop. Called every server tick.
     * Returns true if combat is active.
     */
    public boolean tick() {
        if (state == State.IDLE) {
            return false;
        }

        // Validate target still exists and is alive
        Entity freshEntity = bot.serverLevel().getEntity(targetEntityId);
        if (freshEntity == null || !(freshEntity instanceof LivingEntity living)) {
            stopCombat("target_gone");
            return false;
        }
        if (!living.isAlive()) {
            stopCombat("target_dead");
            return false;
        }
        this.target = living;

        // Check bot alive
        if (!bot.isAlive()) {
            stopCombat("bot_died");
            return false;
        }

        // Range check — give up if target too far
        double dist = bot.distanceTo(target);
        if (dist > VIEW_DISTANCE) {
            stopCombat("target_escaped");
            return false;
        }

        // Look at target every tick
        lookAtEntity(target);

        ticksSinceRepath++;
        ticksSinceLastAttack++;

        // Periodic state summary (every 20 ticks = 1 second)
        if (ticksSinceRepath % 20 == 0) {
            BridgeMod.LOGGER.debug("Bot '{}' combat: state={}, dist={}, bot=({},{},{}), target=({},{},{}), nav={}",
                    bot.getBotName(), state,
                    String.format("%.1f", dist),
                    String.format("%.1f", bot.getX()), String.format("%.1f", bot.getY()), String.format("%.1f", bot.getZ()),
                    String.format("%.1f", target.getX()), String.format("%.1f", target.getY()), String.format("%.1f", target.getZ()),
                    navController.isNavigating());
        }

        switch (state) {
            case PURSUING:
                tickPursuing(dist);
                break;
            case MELEE:
                tickMelee(dist);
                break;
        }

        return true;
    }

    // ==================== State: PURSUING ====================

    private void tickPursuing(double dist) {
        // Close enough → switch to melee
        if (dist <= MELEE_CLOSE) {
            navController.stop();
            enterMelee();
            return;
        }

        // Check if target moved significantly → re-pathfind
        if (ticksSinceRepath >= REPATH_INTERVAL) {
            double dx = target.getX() - lastTargetX;
            double dy = target.getY() - lastTargetY;
            double dz = target.getZ() - lastTargetZ;
            double movedSq = dx * dx + dy * dy + dz * dz;

            if (movedSq >= REPATH_DIST_SQ || !navController.isNavigating()) {
                startPursuit();
            }
        }

        // Navigation finished but not close enough → re-pursue
        if (!navController.isNavigating() && dist > MELEE_CLOSE) {
            startPursuit();
        }

        // Try to attack while pursuing (opportunistic)
        attemptNormalAttack(dist);
    }

    // ==================== State: MELEE ====================

    private void tickMelee(double dist) {
        // Target moved out of range → resume pursuit
        if (dist > MELEE_EXIT) {
            resetAllMeleeState();
            startPursuit();
            return;
        }

        // === 1. KB Cancel: react to being hit (highest priority) ===
        if (config.kbCancelMode != CombatConfig.KBCancelMode.NONE) {
            tickKBCancel();
        }

        // === 2. Shield Breaking: detect opponent blocking → switch to axe ===
        if (shieldBreakCooldown > 0) {
            shieldBreakCooldown--;
            if (shieldBreakCooldown == 0 && previousWeaponSlot >= 0) {
                bot.equipToMainHand(previousWeaponSlot);
                previousWeaponSlot = -1;
            }
        }
        if (config.shieldBreaking && shieldBreakCooldown <= 0 && target.isBlocking()) {
            handleShieldBreak();
        }

        // === 3. Compute strafe direction ===
        float strafeValue = computeStrafeValue();

        // === 4. Reactionary Crit: opportunistic crit when knocked airborne ===
        if (config.reactionaryCrit) {
            if (tickReactionaryCrit(dist)) {
                return; // crit executed or waiting; skip normal attack mode
            }
        }

        // === 5. Backoff on hit trigger ===
        if (config.backoffOnHitTicks > 0 && bot.hurtTime == 9) {
            backoffTicksRemaining = config.backoffOnHitTicks;
        }

        // === 6. Distance management: compute forward ===
        float computedForward = computeForward(dist, strafeValue);

        // === 7. Dispatch to attack mode ===
        switch (config.attackMode) {
            case CRIT:
                tickCritAttack(dist, strafeValue, computedForward);
                break;
            case WTAP:
                tickWtapAttack(dist, strafeValue, computedForward);
                break;
            case STAP:
                tickStapAttack(dist, strafeValue, computedForward);
                break;
            default:
                tickNormalMelee(dist, strafeValue, computedForward);
                break;
        }

        // === 8. Auto-shield between attacks ===
        if (config.autoShield) {
            tickAutoShield();
        }
    }

    // ==================== KB Cancel ====================

    private void tickKBCancel() {
        // Detect new hit
        if (bot.hurtTime == 9 && !kbCancelActive) {
            // Verify damage came from our combat target (not environment)
            LivingEntity attacker = bot.getLastHurtByMob();
            if (attacker == null || attacker != target) {
                return;
            }

            kbCancelActive = true;

            if (config.kbCancelMode == CombatConfig.KBCancelMode.JUMP) {
                // Sprint + jump forward to counter knockback with momentum
                bot.setSprinting(true);
                bot.setMovementInput(1.0f, 0.0f, true);
                kbCancelTicksRemaining = 1;
                BridgeMod.LOGGER.debug("Bot '{}' KB CANCEL JUMP triggered (attacker={})",
                        bot.getBotName(), attacker.getId());

                // Reset attack sub-states (positioning is disrupted)
                resetCritState();
                stapPhase = StapPhase.APPROACH;
                stapBackTicksRemaining = 0;
            } else if (config.kbCancelMode == CombatConfig.KBCancelMode.SHIFT) {
                // Sneak to reduce knockback received
                bot.setShiftKeyDown(true);
                kbCancelTicksRemaining = config.kbCancelShiftTicks;
                BridgeMod.LOGGER.debug("Bot '{}' KB CANCEL SHIFT triggered ({} ticks, attacker={})",
                        bot.getBotName(), config.kbCancelShiftTicks, attacker.getId());
            }
        }

        // Tick down active KB cancel
        if (kbCancelActive && kbCancelTicksRemaining > 0) {
            kbCancelTicksRemaining--;
            if (kbCancelTicksRemaining <= 0) {
                kbCancelActive = false;
                if (config.kbCancelMode == CombatConfig.KBCancelMode.SHIFT) {
                    bot.setShiftKeyDown(false);
                }
            }
        }
    }

    // ==================== Reactionary Crit ====================

    /**
     * Opportunistic crit when knocked airborne by an enemy hit.
     * Returns true if reactionary crit is active (skip normal attack mode).
     */
    private boolean tickReactionaryCrit(double dist) {
        // Detect being knocked into the air
        if (bot.hurtTime == 9 && !bot.onGround()) {
            reactionaryCritActive = true;
            reactionaryCritTicks = 0;
        }

        if (!reactionaryCritActive) {
            return false;
        }

        reactionaryCritTicks++;

        // Give up after 12 ticks or landing
        if (reactionaryCritTicks > 12 || bot.onGround()) {
            reactionaryCritActive = false;
            reactionaryCritTicks = 0;
            return false;
        }

        // Wait for falling: past apex, actually descending
        Vec3 vel = bot.getDeltaMovement();
        if (bot.fallDistance > 0.0f && vel.y <= -0.25) {
            float requiredDelay = getHeldItemAttackDelay();
            if (ticksSinceLastAttack >= (int) requiredDelay
                    && dist <= ATTACK_RANGE
                    && bot.hasLineOfSight(target)) {
                BridgeMod.LOGGER.info("Bot '{}' REACTIONARY CRIT on entity {} (fallDist={}, vel.y={})",
                        bot.getBotName(), target.getId(),
                        String.format("%.2f", bot.fallDistance),
                        String.format("%.2f", vel.y));
                executeCritAttack(dist);
                reactionaryCritActive = false;
                reactionaryCritTicks = 0;
                return true;
            }
        }

        // Still waiting in the air
        return true;
    }

    // ==================== Distance Management ====================

    /**
     * Compute forward input based on distance management config.
     */
    private float computeForward(double dist, float strafeValue) {
        if (backoffTicksRemaining > 0) {
            backoffTicksRemaining--;
            return -0.3f;
        }
        if (config.tooCloseRange > 0.0f && dist < config.tooCloseRange) {
            return 0.0f;
        }
        // Normal: reduce forward when strafing close
        if (strafeValue != 0.0f && dist < 2.0) {
            return 0.3f;
        }
        return 1.0f;
    }

    // ==================== Attack Mode: NORMAL ====================

    private void tickNormalMelee(double dist, float strafeValue, float forward) {
        bot.setSprinting(dist > 1.5);
        bot.setMovementInput(forward, strafeValue, false);
        attemptNormalAttack(dist);
    }

    /** Normal attack: check cooldown + range + LOS → attack. */
    private void attemptNormalAttack(double dist) {
        if (dist > ATTACK_RANGE) {
            return;
        }

        float requiredDelay = getHeldItemAttackDelay();
        if (ticksSinceLastAttack < (int) requiredDelay) {
            return;
        }

        if (!bot.hasLineOfSight(target)) {
            return;
        }

        // Lower shield before attacking
        stopShielding();

        BridgeMod.LOGGER.info("Bot '{}' ATTACKING entity {} (dist={}, ticks={})",
                bot.getBotName(), target.getId(),
                String.format("%.1f", dist), ticksSinceLastAttack);

        bot.setForceFullAttackStrength(true);
        bot.attack(target);
        bot.setForceFullAttackStrength(false);
        bot.swing(InteractionHand.MAIN_HAND);
        ticksSinceLastAttack = 0;
    }

    // ==================== Attack Mode: CRIT ====================

    /**
     * Critical hit via hop: jump before cooldown ready, attack while falling.
     * Vanilla crit conditions: fallDistance > 0, !onGround, !sprinting, !inWater, !onClimbable.
     */
    private void tickCritAttack(double dist, float strafeValue, float forward) {
        float requiredDelay = getHeldItemAttackDelay();
        int ticksRemaining = (int) requiredDelay - ticksSinceLastAttack;

        switch (critPhase) {
            case IDLE:
                // Walk forward, NO sprint (crit requires !sprinting)
                bot.setSprinting(false);
                bot.setMovementInput(forward, strafeValue, false);

                // Initiate jump when cooldown is approaching
                if (ticksRemaining <= config.critJumpLeadTicks && dist <= ATTACK_RANGE) {
                    critPhase = CritPhase.JUMPING;
                    bot.setMovementInput(1.0f, strafeValue, true); // jump=true, always forward
                }
                break;

            case JUMPING:
                // Jump initiated, wait to leave ground
                bot.setSprinting(false);
                bot.setMovementInput(1.0f, strafeValue, true);

                if (!bot.onGround()) {
                    critPhase = CritPhase.FALLING;
                }
                break;

            case FALLING:
                // Airborne — wait for fallDistance > 0 (past jump apex, now descending)
                bot.setSprinting(false);
                bot.setMovementInput(1.0f, strafeValue, false);

                if (bot.fallDistance > 0.0f && !bot.onGround()) {
                    critPhase = CritPhase.ATTACK_READY;
                }

                // Safety: landed without reaching attack_ready
                if (bot.onGround() && bot.fallDistance == 0) {
                    critPhase = CritPhase.IDLE;
                }
                break;

            case ATTACK_READY:
                // Falling + ready → execute crit
                bot.setSprinting(false);
                bot.setMovementInput(1.0f, strafeValue, false);

                if (dist <= ATTACK_RANGE && bot.hasLineOfSight(target)) {
                    executeCritAttack(dist);
                    critPhase = CritPhase.IDLE;
                }

                // Safety: landed without attacking
                if (bot.onGround()) {
                    critPhase = CritPhase.IDLE;
                }
                break;
        }
    }

    private void executeCritAttack(double dist) {
        stopShielding();
        bot.setSprinting(false); // vanilla crit check: !isSprinting()

        BridgeMod.LOGGER.info("Bot '{}' CRIT ATTACK entity {} (dist={}, fallDist={}, ticks={})",
                bot.getBotName(), target.getId(),
                String.format("%.1f", dist),
                String.format("%.2f", bot.fallDistance),
                ticksSinceLastAttack);

        bot.setForceFullAttackStrength(true);
        bot.attack(target);
        bot.setForceFullAttackStrength(false);
        bot.swing(InteractionHand.MAIN_HAND);
        ticksSinceLastAttack = 0;
    }

    // ==================== Attack Mode: WTAP ====================

    /**
     * W-tap: reset sprint before each attack so every hit gets +1 knockback bonus.
     * Vanilla Player.attack() checks isSprinting() → grants extra knockback.
     */
    private void tickWtapAttack(double dist, float strafeValue, float forward) {
        // Sprint toward target
        bot.setSprinting(dist > 1.5);
        bot.setMovementInput(forward, strafeValue, false);

        float requiredDelay = getHeldItemAttackDelay();
        if (ticksSinceLastAttack < (int) requiredDelay) {
            return;
        }

        if (dist > ATTACK_RANGE || !bot.hasLineOfSight(target)) {
            return;
        }

        stopShielding();

        // W-tap: toggle sprint off→on in same tick to reset "first sprint hit" flag
        bot.setSprinting(false);
        bot.setSprinting(true);

        BridgeMod.LOGGER.info("Bot '{}' WTAP ATTACK entity {} (dist={}, ticks={})",
                bot.getBotName(), target.getId(),
                String.format("%.1f", dist), ticksSinceLastAttack);

        bot.setForceFullAttackStrength(true);
        bot.attack(target);
        bot.setForceFullAttackStrength(false);
        bot.swing(InteractionHand.MAIN_HAND);
        ticksSinceLastAttack = 0;
    }

    // ==================== Attack Mode: STAP ====================

    /**
     * S-tap: backward tap after hitting to create spacing while resetting sprint.
     * Phase: APPROACH → (attack) → BACKING → RE_ENGAGE → APPROACH
     */
    private void tickStapAttack(double dist, float strafeValue, float forward) {
        float requiredDelay = getHeldItemAttackDelay();

        switch (stapPhase) {
            case APPROACH:
                // Sprint toward target
                bot.setSprinting(dist > 1.5);
                bot.setMovementInput(forward, strafeValue, false);

                // Check cooldown + range + LOS → attack
                if (ticksSinceLastAttack >= (int) requiredDelay
                        && dist <= ATTACK_RANGE
                        && bot.hasLineOfSight(target)) {
                    stopShielding();

                    // Sprint reset (same as W-tap)
                    bot.setSprinting(false);
                    bot.setSprinting(true);

                    BridgeMod.LOGGER.info("Bot '{}' STAP ATTACK entity {} (dist={})",
                            bot.getBotName(), target.getId(), String.format("%.1f", dist));

                    bot.setForceFullAttackStrength(true);
                    bot.attack(target);
                    bot.setForceFullAttackStrength(false);
                    bot.swing(InteractionHand.MAIN_HAND);
                    ticksSinceLastAttack = 0;

                    // Enter backing phase
                    stapPhase = StapPhase.BACKING;
                    stapBackTicksRemaining = config.stapBackTicks;
                    BridgeMod.LOGGER.debug("Bot '{}' STAP → BACKING ({} ticks, dist={})",
                            bot.getBotName(), config.stapBackTicks, String.format("%.1f", dist));
                }
                break;

            case BACKING:
                // Walk backward, no sprint
                bot.setSprinting(false);
                bot.setMovementInput(-1.0f, strafeValue, false);
                stapBackTicksRemaining--;

                if (stapBackTicksRemaining <= 0) {
                    stapPhase = StapPhase.RE_ENGAGE;
                    BridgeMod.LOGGER.debug("Bot '{}' STAP → RE_ENGAGE (dist={})",
                            bot.getBotName(), String.format("%.1f", dist));
                }
                break;

            case RE_ENGAGE:
                // Sprint forward to close gap
                bot.setSprinting(true);
                bot.setMovementInput(1.0f, strafeValue, false);

                // Back to approach when cooldown is near or close enough
                int ticksUntilReady = (int) requiredDelay - ticksSinceLastAttack;
                if (ticksUntilReady <= 6 || dist < 2.0) {
                    stapPhase = StapPhase.APPROACH;
                    BridgeMod.LOGGER.debug("Bot '{}' STAP → APPROACH (ticksUntilReady={}, dist={})",
                            bot.getBotName(), ticksUntilReady, String.format("%.1f", dist));
                }
                break;
        }
    }

    // ==================== Strafe/Dodge ====================

    /**
     * Compute strafe input value based on configured strafe mode.
     * Returns strafe value for setMovementInput (-1.0 to 1.0).
     */
    private float computeStrafeValue() {
        if (config.strafeMode == CombatConfig.StrafeMode.NONE) {
            return 0.0f;
        }

        ticksSinceStrafeChange++;

        switch (config.strafeMode) {
            case CIRCLE:
                // Consistent circular strafing, periodic direction switch
                if (ticksSinceStrafeChange >= config.strafeChangeIntervalTicks) {
                    currentStrafeDirection = -currentStrafeDirection;
                    ticksSinceStrafeChange = 0;
                }
                return currentStrafeDirection * config.strafeIntensity;

            case RANDOM:
                // Random direction switches
                if (ticksSinceStrafeChange >= config.strafeChangeIntervalTicks) {
                    currentStrafeDirection = ThreadLocalRandom.current().nextBoolean() ? 1.0f : -1.0f;
                    ticksSinceStrafeChange = 0;
                }
                return currentStrafeDirection * config.strafeIntensity;

            case INTELLIGENT:
                // React to incoming hits: reverse direction when hurt
                if (bot.hurtTime == 9) { // just got hit (hurtTime set to 10, decrements)
                    currentStrafeDirection = -currentStrafeDirection;
                    ticksSinceStrafeChange = 0;
                } else if (ticksSinceStrafeChange >= config.strafeChangeIntervalTicks) {
                    currentStrafeDirection = ThreadLocalRandom.current().nextBoolean() ? 1.0f : -1.0f;
                    ticksSinceStrafeChange = 0;
                }
                return currentStrafeDirection * config.strafeIntensity;

            default:
                return 0.0f;
        }
    }

    // ==================== Shield Handling ====================

    /**
     * Auto-switch to axe when target is blocking with shield.
     * Axe hit disables shields for 5 seconds (vanilla mechanic).
     */
    private void handleShieldBreak() {
        int axeSlot = findAxeSlot();
        if (axeSlot < 0) return; // no axe in hotbar

        previousWeaponSlot = bot.getInventory().selected;
        bot.equipToMainHand(axeSlot);
        shieldBreakCooldown = 5; // switch back after 5 ticks (one attack cycle)

        BridgeMod.LOGGER.info("Bot '{}' shield break: switching to axe slot {}",
                bot.getBotName(), axeSlot);
    }

    /** Find first axe in hotbar (slots 0-8). Returns -1 if none found. */
    private int findAxeSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Raise shield between attacks for defense.
     * Shield is lowered ~3 ticks before the next attack.
     */
    private void tickAutoShield() {
        // Check if bot has a shield in offhand
        ItemStack offhand = bot.getOffhandItem();
        if (offhand.isEmpty() || !(offhand.getItem() instanceof ShieldItem)) {
            return;
        }

        float requiredDelay = getHeldItemAttackDelay();
        int ticksUntilAttack = (int) requiredDelay - ticksSinceLastAttack;

        if (ticksUntilAttack > 3 && !isShielding) {
            // Raise shield between attacks
            bot.startUsingItem(InteractionHand.OFF_HAND);
            isShielding = true;
        } else if (ticksUntilAttack <= 3 && isShielding) {
            // Lower shield before attacking
            stopShielding();
        }
    }

    private void stopShielding() {
        if (isShielding) {
            bot.stopUsingItem();
            isShielding = false;
        }
    }

    // ==================== State Transitions ====================

    /**
     * Start pathfinding pursuit to target's current position.
     * Delegates to BotController.startGoto() with GoalNear(range=PURSUIT_RANGE).
     */
    private void startPursuit() {
        state = State.PURSUING;
        lastTargetX = target.getX();
        lastTargetY = target.getY();
        lastTargetZ = target.getZ();
        ticksSinceRepath = 0;

        // Stop any existing navigation
        if (navController.isNavigating()) {
            navController.stop();
        }

        navController.startGoto(
                target.getX(), target.getY(), target.getZ(),
                PURSUIT_RANGE,
                (success, reason) -> {
                    // Navigation callback — check if we should enter melee.
                    // Only act if still in PURSUING state (may have been cancelled).
                    if (state == State.PURSUING && target != null) {
                        double d = bot.distanceTo(target);
                        if (d <= MELEE_CLOSE) {
                            enterMelee();
                        }
                        // Otherwise tick() will see !isNavigating() and re-pursue
                    }
                }
        );

        BridgeMod.LOGGER.debug("Bot '{}' pursuing target at ({}, {}, {})",
                bot.getBotName(), (int) lastTargetX, (int) lastTargetY, (int) lastTargetZ);
    }

    private void enterMelee() {
        state = State.MELEE;
        lookAtEntity(target);
        bot.setMovementInput(1.0f, 0.0f, false);
        bot.setSprinting(config.attackMode != CombatConfig.AttackMode.CRIT);
        stapPhase = StapPhase.APPROACH;
        stapBackTicksRemaining = 0;
        backoffTicksRemaining = 0;
        BridgeMod.LOGGER.info("Bot '{}' entered MELEE state (dist={}, mode={}, strafe={})",
                bot.getBotName(), String.format("%.1f", bot.distanceTo(target)),
                config.attackMode, config.strafeMode);
    }

    private void stopCombat(String reason) {
        State prevState = state;
        state = State.IDLE;
        targetEntityId = -1;
        target = null;
        ticksSinceRepath = 0;
        ticksSinceLastAttack = 100; // ready for next fight

        // Reset advanced combat state
        resetCritState();
        stopShielding();
        shieldBreakCooldown = 0;
        previousWeaponSlot = -1;
        ticksSinceStrafeChange = 0;

        // Reset new combat enhancements
        kbCancelActive = false;
        kbCancelTicksRemaining = 0;
        if (config.kbCancelMode == CombatConfig.KBCancelMode.SHIFT) {
            bot.setShiftKeyDown(false);
        }
        reactionaryCritActive = false;
        reactionaryCritTicks = 0;
        stapPhase = StapPhase.APPROACH;
        stapBackTicksRemaining = 0;
        backoffTicksRemaining = 0;

        // Stop navigation if pursuing
        if (prevState == State.PURSUING && navController.isNavigating()) {
            navController.stop();
        }

        // Clear movement
        bot.clearMovementInput();
        bot.setSprinting(false);

        // Auto-attack mode: find next hostile after a kill, or end if cancelled/no more targets
        if (autoAttackMode) {
            if ("target_dead".equals(reason)) {
                killCount++;
                BridgeMod.LOGGER.info("Bot '{}' auto-attack: kill #{}, scanning for next hostile...",
                        bot.getBotName(), killCount);
                if (findAndAttackNextHostile()) {
                    return; // found new target, keep going
                }
                // No more hostiles
                autoAttackMode = false;
                BiConsumer<Boolean, String> aaCb = autoAttackCallback;
                autoAttackCallback = null;
                if (aaCb != null) {
                    aaCb.accept(true, "all_clear:" + killCount);
                }
                BridgeMod.LOGGER.info("Bot '{}' auto-attack: area clear ({} kills)", bot.getBotName(), killCount);
                return;
            } else if ("target_gone".equals(reason) || "target_escaped".equals(reason)) {
                // Target disappeared, try to find another
                if (findAndAttackNextHostile()) {
                    return;
                }
                autoAttackMode = false;
                BiConsumer<Boolean, String> aaCb = autoAttackCallback;
                autoAttackCallback = null;
                if (aaCb != null) {
                    aaCb.accept(true, "all_clear:" + killCount);
                }
                return;
            } else {
                // Cancelled or bot died — end auto-attack
                autoAttackMode = false;
                BiConsumer<Boolean, String> aaCb = autoAttackCallback;
                autoAttackCallback = null;
                if (aaCb != null) {
                    aaCb.accept(false, reason + ":" + killCount);
                }
                BridgeMod.LOGGER.info("Bot '{}' auto-attack ended: {} ({} kills)",
                        bot.getBotName(), reason, killCount);
                return;
            }
        }

        // Single-target mode: fire callback
        BiConsumer<Boolean, String> cb = this.callback;
        this.callback = null;
        if (cb != null) {
            boolean success = "target_dead".equals(reason);
            cb.accept(success, reason);
        }

        BridgeMod.LOGGER.info("Bot '{}' combat ended: {}", bot.getBotName(), reason);
    }

    private void resetCritState() {
        critPhase = CritPhase.IDLE;
    }

    /** Reset all melee sub-state when leaving melee (to pursuit or stop). */
    private void resetAllMeleeState() {
        resetCritState();
        stopShielding();
        stapPhase = StapPhase.APPROACH;
        stapBackTicksRemaining = 0;
        backoffTicksRemaining = 0;
        reactionaryCritActive = false;
        reactionaryCritTicks = 0;
    }

    // ==================== Helpers ====================

    /**
     * Compute attack cooldown delay in ticks from the held item's attribute modifiers.
     * Mirrors vanilla's getCurrentItemAttackStrengthDelay() but reads item modifiers
     * directly instead of relying on entity attributes (which aren't updated because
     * ServerPlayer.tick() NPEs before LivingEntity.collectEquipmentChanges() runs).
     *
     * Base player attack speed = 4.0. Sword modifier = -2.4 → effective 1.6 → 12.5 ticks.
     */
    private float getHeldItemAttackDelay() {
        double attackSpeed = 4.0; // base player attack speed
        ItemStack held = bot.getMainHandItem();
        if (!held.isEmpty()) {
            var modifiers = held.getAttributeModifiers(EquipmentSlot.MAINHAND);
            var speedMods = modifiers.get(Attributes.ATTACK_SPEED);
            for (var mod : speedMods) {
                if (mod.getOperation() == AttributeModifier.Operation.ADDITION) {
                    attackSpeed += mod.getAmount();
                }
            }
        }
        return (float) (1.0 / attackSpeed * 20.0);
    }

    /**
     * Look at a living entity's eye position.
     * Sets yaw, pitch, and head rotation for accurate knockback direction.
     */
    private void lookAtEntity(LivingEntity entity) {
        Vec3 targetPos = entity.getEyePosition();
        double dx = targetPos.x - bot.getX();
        double dy = targetPos.y - bot.getEyeY();
        double dz = targetPos.z - bot.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);
        float pitch = (float) (Math.atan2(-dy, horizDist) * 180.0 / Math.PI);
        bot.setYRot(yaw);
        bot.setXRot(pitch);
        bot.setYHeadRot(yaw);
    }

    // ==================== Accessors ====================

    public State getState() {
        return state;
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public int getTargetEntityId() {
        return targetEntityId;
    }

    public LivingEntity getTarget() {
        return target;
    }

    public CombatConfig getConfig() {
        return config;
    }
}
