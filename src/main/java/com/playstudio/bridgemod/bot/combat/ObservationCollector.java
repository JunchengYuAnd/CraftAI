package com.playstudio.bridgemod.bot.combat;

import com.playstudio.bridgemod.BridgeMod;
import com.playstudio.bridgemod.bot.FakePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

/**
 * Collects combat observations every tick and feeds them into MobProfileManager.
 *
 * Two data streams:
 * 1. Passive: every tick, observe movement speed of nearby hostile mobs
 * 2. Active: on bot.hurtTime == 9, record hit distance/interval/damage per attacker type
 *
 * Called from CombatController during the combat tick loop.
 */
public class ObservationCollector {

    private final FakePlayer bot;
    private final MobProfileManager profileManager;
    private final double scanRadius;

    // Track bot health for damage estimation
    private float lastKnownHealth = -1;

    // Logging throttle
    private int ticksSinceLastLog = 0;
    private static final int LOG_INTERVAL = 100; // every 5 seconds

    public ObservationCollector(FakePlayer bot, MobProfileManager profileManager, double scanRadius) {
        this.bot = bot;
        this.profileManager = profileManager;
        this.scanRadius = scanRadius;
    }

    /**
     * Call every tick during combat. Observes nearby mob speeds.
     */
    public void tick() {
        if (lastKnownHealth < 0) {
            lastKnownHealth = bot.getHealth();
        }

        // Passive observation: record movement speed of all nearby hostiles
        for (Entity entity : bot.serverLevel().getAllEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!(entity instanceof Enemy)) continue;
            if (!living.isAlive()) continue;
            if (entity == bot) continue;

            double dist = bot.distanceTo(entity);
            if (dist > scanRadius) continue;

            // Record horizontal speed (ignore Y to avoid fall/jump noise)
            Vec3 delta = living.getDeltaMovement();
            double horizontalSpeed = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            if (horizontalSpeed > 0.001) { // filter stationary
                MobProfile profile = profileManager.getProfile(living);
                profile.recordSpeed(horizontalSpeed);
            }
        }

        ticksSinceLastLog++;
    }

    /**
     * Call when bot.hurtTime == 9 (just received damage).
     * Records hit distance, interval, and estimated damage to the attacker's profile.
     *
     * @param attacker the entity that hit the bot (from getLastHurtByMob)
     */
    public void onHit(LivingEntity attacker) {
        if (attacker == null || !attacker.isAlive()) return;

        double distance = bot.distanceTo(attacker);
        int currentTick = bot.tickCount;

        // Read mob's raw attack damage attribute (unaffected by bot's armor/resistance)
        double rawDamage = 0;
        var attackDamageAttr = attacker.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackDamageAttr != null) {
            rawDamage = attackDamageAttr.getValue();
        }

        // Update health tracker (still useful for other purposes)
        lastKnownHealth = bot.getHealth();

        MobProfile profile = profileManager.getProfile(attacker);
        profile.recordHit(distance, currentTick, rawDamage);

        // Log the observation
        String typeId = EntityType.getKey(attacker.getType()).toString();
        BridgeMod.LOGGER.info("MobProfile HIT: {} at dist={} rawDmg={} | {}",
                typeId, String.format("%.2f", distance),
                String.format("%.1f", rawDamage), profile);
    }

    /**
     * Update health tracker (call after any health change not from combat,
     * e.g. healing, or at start of combat).
     */
    public void resetHealthTracker() {
        lastKnownHealth = bot.getHealth();
    }

    /**
     * Log summary of all learned profiles (throttled).
     */
    public void periodicLog() {
        if (ticksSinceLastLog >= LOG_INTERVAL) {
            ticksSinceLastLog = 0;
            if (profileManager.size() > 0) {
                profileManager.logSummary();
            }
        }
    }
}
