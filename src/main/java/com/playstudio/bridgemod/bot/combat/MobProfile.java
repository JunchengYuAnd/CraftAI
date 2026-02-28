package com.playstudio.bridgemod.bot.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Learned combat profile for a specific mob type.
 * Tracks observed attack range, cooldown, movement speed, damage, and ranged status.
 * Updated by ObservationCollector, consumed by ParameterAdapter.
 *
 * Confidence starts at 0.0 (use defaults) and converges toward 1.0
 * as more samples are collected.
 */
public class MobProfile {

    // Sample limits
    private static final int MAX_SAMPLES = 50;
    private static final int HIGH_CONFIDENCE_SAMPLES = 30;
    private static final int MEDIUM_CONFIDENCE_SAMPLES = 15;
    private static final int LOW_CONFIDENCE_SAMPLES = 5;

    // Identity
    public final String entityTypeId;  // e.g. "minecraft:zombie"

    // Learned parameters (initialized with conservative defaults)
    private double attackRange = 3.0;
    private int attackCooldownTicks = 20;    // 1 second
    private double movementSpeed = 0.23;     // zombie walk speed
    private double estimatedDamage = 3.0;    // 1.5 hearts
    private boolean isRanged = false;
    private double threatLevel = 1.0;        // derived: range × damage / cooldown

    // Confidence (0.0 = untrained, 1.0 = high confidence)
    private float rangeConfidence = 0.0f;
    private float cooldownConfidence = 0.0f;
    private float speedConfidence = 0.0f;
    private float damageConfidence = 0.0f;
    private float rangedConfidence = 0.0f;

    // Raw observation samples (ring buffers)
    private final List<Double> hitDistanceSamples = new ArrayList<>();
    private final List<Integer> hitIntervalSamples = new ArrayList<>();
    private final List<Double> speedSamples = new ArrayList<>();
    private final List<Double> damageSamples = new ArrayList<>();

    // Ranged detection: count of hits received at long range vs short range
    private int longRangeHits = 0;  // hit distance > 5.0
    private int shortRangeHits = 0; // hit distance <= 5.0

    // Stats
    private int totalHitsTaken = 0;
    private int totalEncounters = 0;
    private long lastUpdatedTick = 0;

    // For cooldown measurement: tick of last hit from this mob type
    private int lastHitTick = -1000;

    public MobProfile(String entityTypeId) {
        this.entityTypeId = entityTypeId;
    }

    // ==================== Sample Recording ====================

    /** Record a hit received from this mob type at the given distance. */
    public void recordHit(double distance, int currentTick, double healthLost) {
        totalHitsTaken++;
        lastUpdatedTick = currentTick;

        // Attack range sample
        addSample(hitDistanceSamples, distance);

        // Attack cooldown sample (interval between consecutive hits)
        int interval = currentTick - lastHitTick;
        if (lastHitTick > 0 && interval > 0 && interval < 200) {
            // Only record reasonable intervals (< 10 seconds)
            addSample(hitIntervalSamples, interval);
        }
        lastHitTick = currentTick;

        // Damage sample
        if (healthLost > 0) {
            addSample(damageSamples, healthLost);
        }

        // Ranged detection
        if (distance > 5.0) {
            longRangeHits++;
        } else {
            shortRangeHits++;
        }

        // Recompute all derived values
        recompute();
    }

    /** Record an observed movement speed sample for this mob type. */
    public void recordSpeed(double speed) {
        if (speed > 0.001 && speed < 2.0) {  // filter noise and teleport
            addSample(speedSamples, speed);
            if (speedSamples.size() % 10 == 0) {
                recomputeSpeed();
            }
        }
    }

    /** Mark a new encounter with this mob type. */
    public void recordEncounter() {
        totalEncounters++;
    }

    // ==================== Recomputation ====================

    private void recompute() {
        recomputeRange();
        recomputeCooldown();
        recomputeSpeed();
        recomputeDamage();
        recomputeRanged();
        recomputeThreatLevel();
    }

    private void recomputeRange() {
        if (hitDistanceSamples.isEmpty()) return;
        attackRange = percentile(hitDistanceSamples, 95);
        rangeConfidence = sampleConfidence(hitDistanceSamples.size());
    }

    private void recomputeCooldown() {
        if (hitIntervalSamples.isEmpty()) return;
        attackCooldownTicks = (int) Math.round(median(hitIntervalSamples));
        cooldownConfidence = sampleConfidence(hitIntervalSamples.size());
    }

    private void recomputeSpeed() {
        if (speedSamples.isEmpty()) return;
        // Use median to resist outliers (teleport, lag spikes)
        movementSpeed = median(speedSamples);
        speedConfidence = sampleConfidence(speedSamples.size() / 5); // speed samples come fast, need more
    }

    private void recomputeDamage() {
        if (damageSamples.isEmpty()) return;
        estimatedDamage = median(damageSamples);
        damageConfidence = sampleConfidence(damageSamples.size());
    }

    private void recomputeRanged() {
        int total = longRangeHits + shortRangeHits;
        if (total < 3) {
            rangedConfidence = 0.0f;
            return;
        }
        float longRatio = (float) longRangeHits / total;
        isRanged = longRatio > 0.5f;
        rangedConfidence = Math.min(1.0f, total / 10.0f); // 10+ samples → full confidence
    }

    private void recomputeThreatLevel() {
        // threatLevel = (range × damage) / cooldown, normalized
        double cd = Math.max(attackCooldownTicks, 1);
        threatLevel = (attackRange * estimatedDamage) / (cd / 20.0);
        // Normalize: zombie baseline ≈ (2.5 * 3.0) / 1.0 = 7.5
        threatLevel /= 7.5;
    }

    // ==================== Statistical Helpers ====================

    private static double percentile(List<Double> samples, int p) {
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Number> double median(List<T> samples) {
        List<Double> sorted = new ArrayList<>();
        for (T s : samples) sorted.add(s.doubleValue());
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static float sampleConfidence(int sampleCount) {
        if (sampleCount <= 0) return 0.0f;
        if (sampleCount >= HIGH_CONFIDENCE_SAMPLES) return 1.0f;
        if (sampleCount >= MEDIUM_CONFIDENCE_SAMPLES) return 0.8f;
        if (sampleCount >= LOW_CONFIDENCE_SAMPLES) return 0.5f;
        return sampleCount / (float) LOW_CONFIDENCE_SAMPLES * 0.5f;
    }

    private static <T> void addSample(List<T> samples, T value) {
        samples.add(value);
        if (samples.size() > MAX_SAMPLES) {
            samples.remove(0);
        }
    }

    // ==================== Getters ====================

    public double getAttackRange() { return attackRange; }
    public int getAttackCooldownTicks() { return attackCooldownTicks; }
    public double getMovementSpeed() { return movementSpeed; }
    public double getEstimatedDamage() { return estimatedDamage; }
    public boolean isRanged() { return isRanged; }
    public double getThreatLevel() { return threatLevel; }

    public float getRangeConfidence() { return rangeConfidence; }
    public float getCooldownConfidence() { return cooldownConfidence; }
    public float getSpeedConfidence() { return speedConfidence; }
    public float getDamageConfidence() { return damageConfidence; }
    public float getRangedConfidence() { return rangedConfidence; }

    /** Overall confidence: average of key confidences (range, cooldown, speed).
     *  Using average instead of min so partial learning (e.g. just range) still
     *  starts influencing parameters. */
    public float getOverallConfidence() {
        return (rangeConfidence + cooldownConfidence + speedConfidence) / 3.0f;
    }

    public int getTotalHitsTaken() { return totalHitsTaken; }
    public int getTotalEncounters() { return totalEncounters; }
    public long getLastUpdatedTick() { return lastUpdatedTick; }

    // For persistence
    public List<Double> getHitDistanceSamples() { return hitDistanceSamples; }
    public List<Integer> getHitIntervalSamples() { return hitIntervalSamples; }
    public List<Double> getSpeedSamples() { return speedSamples; }
    public List<Double> getDamageSamples() { return damageSamples; }
    public int getLongRangeHits() { return longRangeHits; }
    public int getShortRangeHits() { return shortRangeHits; }
    public int getLastHitTick() { return lastHitTick; }

    // For loading persisted data
    public void setLastHitTick(int tick) { this.lastHitTick = tick; }
    public void setLongRangeHits(int n) { this.longRangeHits = n; }
    public void setShortRangeHits(int n) { this.shortRangeHits = n; }
    public void setTotalHitsTaken(int n) { this.totalHitsTaken = n; }
    public void setTotalEncounters(int n) { this.totalEncounters = n; }
    public void setLastUpdatedTick(long tick) { this.lastUpdatedTick = tick; }

    /** Recompute all derived values from loaded samples. */
    public void recomputeFromLoadedSamples() {
        recompute();
    }

    @Override
    public String toString() {
        return String.format("MobProfile{%s range=%.2f(%.0f%%) cd=%d(%.0f%%) spd=%.3f(%.0f%%) " +
                        "dmg=%.1f(%.0f%%) ranged=%s(%.0f%%) threat=%.2f hits=%d encounters=%d}",
                entityTypeId, attackRange, rangeConfidence * 100, attackCooldownTicks,
                cooldownConfidence * 100, movementSpeed, speedConfidence * 100,
                estimatedDamage, damageConfidence * 100, isRanged, rangedConfidence * 100,
                threatLevel, totalHitsTaken, totalEncounters);
    }
}
