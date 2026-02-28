package com.playstudio.bridgemod.bot.combat;

/**
 * Maps learned MobProfile data → combat system parameters.
 *
 * Core principle: confidence-weighted blending.
 *   result = confidence × learnedValue + (1 - confidence) × defaultValue
 *
 * At confidence 0.0 → 100% default (safe, conservative)
 * At confidence 1.0 → 95% learned + 5% default (never fully abandon defaults)
 */
public class ParameterAdapter {

    // Maximum blend ratio for learned values (never 100% to keep safety margin)
    private static final float MAX_LEARNED_WEIGHT = 0.95f;

    // Default values (same as current hardcoded constants)
    private static final double DEFAULT_ATTACK_RANGE = 3.0;
    private static final double DEFAULT_MELEE_CLOSE = 2.5;
    private static final double DEFAULT_MELEE_EXIT = 4.0;
    private static final double DEFAULT_OPTIMAL_DISTANCE = 3.0;
    private static final double DEFAULT_THREAT_REPULSION_K = 3.0;
    private static final int DEFAULT_THREAT_MEMORY_TICKS = 60;
    private static final int DEFAULT_PURSUIT_DODGE_TICKS = 5;
    private static final double DEFAULT_MOVEMENT_SPEED = 0.23;

    private ParameterAdapter() {} // utility class

    // ==================== Distance Parameters ====================

    /**
     * Optimal melee distance = learned attack range + buffer.
     * Low confidence → conservative (wider buffer).
     *
     * For RANGED mobs (skeleton, etc.): rush in close — standing outside
     * their 13+ block range is useless for melee combat.
     * Use a tight distance (2.5) so the bot closes gap aggressively.
     */
    public static double computeOptimalDistance(MobProfile profile, double configDefault) {
        // Ranged mob: rush in close instead of staying outside their huge range
        if (profile.isRanged() && profile.getRangedConfidence() >= 0.5f) {
            return 2.5; // tight melee distance — get in their face
        }

        double learnedRange = profile.getAttackRange();
        double buffer = 1.0;
        // Low confidence → extra safety margin
        if (profile.getRangeConfidence() < 0.5f) {
            buffer += 0.5;
        }
        double learned = learnedRange + buffer;
        return blend(learned, configDefault, profile.getRangeConfidence());
    }

    /**
     * Dynamic optimal distance based on threat count.
     * Scales from learned base distance + threat bonus.
     */
    public static double computeOptimalDistanceWithThreats(MobProfile profile, double configDefault, int threatCount) {
        double base = computeOptimalDistance(profile, configDefault);
        double threatBonus = Math.min(threatCount * 0.3, 1.5);
        return base + threatBonus;
    }

    /**
     * Attack range gate (when to attempt an attack).
     * Uses learned range directly — this is offensive, so we want precision.
     */
    public static double computeAttackRange(MobProfile profile) {
        // Bot's own attack range is fixed at ~3.0 (sword reach).
        // This is about the BOT's reach, not the mob's range.
        // We don't change this based on mob profile.
        return DEFAULT_ATTACK_RANGE;
    }

    /**
     * Distance threshold to enter melee state.
     * Based on mob's attack range — enter melee when inside mob's danger zone.
     * Ranged mobs: use standard melee thresholds (bot needs to get close).
     */
    public static double computeMeleeCloseThreshold(MobProfile profile) {
        if (profile.isRanged() && profile.getRangedConfidence() >= 0.5f) {
            return DEFAULT_MELEE_CLOSE; // standard melee threshold for ranged targets
        }
        double learned = profile.getAttackRange() - 0.5;
        return blend(learned, DEFAULT_MELEE_CLOSE, profile.getRangeConfidence());
    }

    /**
     * Distance threshold to exit melee and resume pursuit.
     * Ranged mobs: use standard threshold — don't back off to 14+ blocks.
     */
    public static double computeMeleeExitThreshold(MobProfile profile) {
        if (profile.isRanged() && profile.getRangedConfidence() >= 0.5f) {
            return DEFAULT_MELEE_EXIT; // standard exit threshold for ranged targets
        }
        double learned = profile.getAttackRange() + 1.0;
        return blend(learned, DEFAULT_MELEE_EXIT, profile.getRangeConfidence());
    }

    // ==================== Threat Parameters ====================

    /**
     * Threat repulsion strength, scaled by mob speed.
     * Faster mobs need stronger repulsion.
     */
    public static double computeThreatRepulsionK(MobProfile profile, double configDefault) {
        double speedRatio = profile.getMovementSpeed() / DEFAULT_MOVEMENT_SPEED;
        double learned = configDefault * speedRatio;
        return blend(learned, configDefault, profile.getSpeedConfidence());
    }

    /**
     * Threat memory duration, scaled by attack cooldown.
     * Fast attackers → longer memory (they re-engage quickly).
     */
    public static int computeThreatMemoryTicks(MobProfile profile) {
        int learned = profile.getAttackCooldownTicks() * 3;
        learned = Math.max(20, Math.min(learned, 200)); // clamp 1s ~ 10s
        return (int) blend(learned, DEFAULT_THREAT_MEMORY_TICKS, profile.getCooldownConfidence());
    }

    /**
     * Pursuit dodge duration, scaled by attack frequency.
     */
    public static int computePursuitDodgeTicks(MobProfile profile) {
        int learned = Math.max(3, profile.getAttackCooldownTicks() / 4);
        learned = Math.min(learned, 15); // cap at 15 ticks
        return (int) blend(learned, DEFAULT_PURSUIT_DODGE_TICKS, profile.getCooldownConfidence());
    }

    // ==================== Threat Level for Target Priority ====================

    /**
     * Target priority score: higher = more urgent to deal with.
     * threatLevel / (distance + 1) — close dangerous mobs rank highest.
     */
    public static double computeTargetPriority(MobProfile profile, double distance) {
        return profile.getThreatLevel() / (distance + 1.0);
    }

    /**
     * Per-threat repulsion weight (for mixed-type threat groups).
     * High threat level → stronger repulsion.
     */
    public static double computePerThreatWeight(MobProfile profile) {
        return Math.max(0.5, Math.min(profile.getThreatLevel(), 3.0));
    }

    // ==================== Blending ====================

    /**
     * Confidence-weighted blend between learned and default values.
     * At confidence 0 → 100% default.
     * At confidence 1 → MAX_LEARNED_WEIGHT% learned + (1-MAX_LEARNED_WEIGHT)% default.
     */
    private static double blend(double learned, double defaultValue, float confidence) {
        float clampedConf = Math.max(0.0f, Math.min(confidence, 1.0f));
        float learnedWeight = clampedConf * MAX_LEARNED_WEIGHT;
        return learnedWeight * learned + (1.0f - learnedWeight) * defaultValue;
    }
}
