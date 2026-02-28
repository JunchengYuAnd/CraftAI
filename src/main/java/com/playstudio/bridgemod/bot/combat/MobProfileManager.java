package com.playstudio.bridgemod.bot.combat;

import com.playstudio.bridgemod.BridgeMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MobProfile instances — one per entity type.
 * Thread-safe: profiles are stored in a ConcurrentHashMap.
 *
 * Each FakePlayer's CombatController holds its own MobProfileManager,
 * but profiles could be shared across bots in the future.
 */
public class MobProfileManager {

    private final ConcurrentHashMap<String, MobProfile> profiles = new ConcurrentHashMap<>();

    /**
     * Get or create a profile for the given entity's type.
     * Never returns null.
     */
    public MobProfile getProfile(LivingEntity entity) {
        String typeId = EntityType.getKey(entity.getType()).toString();
        return profiles.computeIfAbsent(typeId, MobProfile::new);
    }

    /**
     * Get or create a profile by type ID string.
     */
    public MobProfile getProfile(String entityTypeId) {
        return profiles.computeIfAbsent(entityTypeId, MobProfile::new);
    }

    /**
     * Get an existing profile, or null if not yet learned.
     */
    public MobProfile getProfileIfExists(String entityTypeId) {
        return profiles.get(entityTypeId);
    }

    /**
     * Get all known profiles.
     */
    public Collection<MobProfile> getAllProfiles() {
        return profiles.values();
    }

    /**
     * Reset a specific mob type's profile (remove all learned data).
     */
    public void resetProfile(String entityTypeId) {
        profiles.remove(entityTypeId);
        BridgeMod.LOGGER.info("MobProfile reset for: {}", entityTypeId);
    }

    /**
     * Reset all profiles.
     */
    public void resetAll() {
        int count = profiles.size();
        profiles.clear();
        BridgeMod.LOGGER.info("All MobProfiles reset ({} profiles)", count);
    }

    /**
     * Put a profile directly (used when loading from persistence).
     */
    public void putProfile(MobProfile profile) {
        profiles.put(profile.entityTypeId, profile);
    }

    /**
     * Number of known mob types.
     */
    public int size() {
        return profiles.size();
    }

    /**
     * Log summary of all profiles.
     */
    public void logSummary() {
        if (profiles.isEmpty()) {
            BridgeMod.LOGGER.info("MobProfileManager: no profiles learned yet");
            return;
        }
        BridgeMod.LOGGER.info("MobProfileManager: {} profiles learned", profiles.size());
        for (MobProfile p : profiles.values()) {
            BridgeMod.LOGGER.info("  {}", p);
        }
    }
}
