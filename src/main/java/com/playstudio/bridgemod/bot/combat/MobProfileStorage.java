package com.playstudio.bridgemod.bot.combat;

import com.google.gson.*;
import com.playstudio.bridgemod.BridgeMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON persistence for MobProfiles.
 * Saves/loads profiles to config/craftai/mob_profiles/ directory.
 * Each mob type gets its own file (e.g. minecraft_zombie.json).
 */
public class MobProfileStorage {

    private static final String DIR_NAME = "config/craftai/mob_profiles";

    private MobProfileStorage() {} // utility class

    /**
     * Save all profiles in the manager to JSON files.
     */
    public static void saveAll(MobProfileManager manager, Path gameDir) {
        Path dir = gameDir.resolve(DIR_NAME);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            BridgeMod.LOGGER.error("Failed to create mob_profiles directory", e);
            return;
        }

        int saved = 0;
        for (MobProfile profile : manager.getAllProfiles()) {
            if (profile.getTotalHitsTaken() == 0 && profile.getSpeedSamples().isEmpty()) {
                continue; // skip empty profiles
            }
            try {
                String filename = profile.entityTypeId.replace(":", "_") + ".json";
                Path file = dir.resolve(filename);
                String json = profileToJson(profile);
                Files.writeString(file, json);
                saved++;
            } catch (IOException e) {
                BridgeMod.LOGGER.error("Failed to save profile for {}", profile.entityTypeId, e);
            }
        }
        if (saved > 0) {
            BridgeMod.LOGGER.info("Saved {} mob profiles to {}", saved, dir);
        }
    }

    /**
     * Load all profiles from JSON files into the manager.
     */
    public static void loadAll(MobProfileManager manager, Path gameDir) {
        Path dir = gameDir.resolve(DIR_NAME);
        if (!Files.isDirectory(dir)) {
            return; // no saved profiles yet
        }

        int loaded = 0;
        try {
            for (Path file : Files.list(dir).filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    String json = Files.readString(file);
                    MobProfile profile = profileFromJson(json);
                    if (profile != null) {
                        manager.putProfile(profile);
                        loaded++;
                    }
                } catch (Exception e) {
                    BridgeMod.LOGGER.warn("Failed to load profile from {}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            BridgeMod.LOGGER.error("Failed to list mob_profiles directory", e);
        }

        if (loaded > 0) {
            BridgeMod.LOGGER.info("Loaded {} mob profiles from {}", loaded, dir);
            manager.logSummary();
        }
    }

    // ==================== JSON Serialization ====================

    private static String profileToJson(MobProfile p) {
        JsonObject obj = new JsonObject();
        obj.addProperty("entityTypeId", p.entityTypeId);
        obj.addProperty("totalHitsTaken", p.getTotalHitsTaken());
        obj.addProperty("totalEncounters", p.getTotalEncounters());
        obj.addProperty("lastUpdatedTick", p.getLastUpdatedTick());
        obj.addProperty("lastHitTick", p.getLastHitTick());
        obj.addProperty("longRangeHits", p.getLongRangeHits());
        obj.addProperty("shortRangeHits", p.getShortRangeHits());

        obj.add("hitDistanceSamples", doublesToArray(p.getHitDistanceSamples()));
        obj.add("hitIntervalSamples", intsToArray(p.getHitIntervalSamples()));
        obj.add("speedSamples", doublesToArray(p.getSpeedSamples()));
        obj.add("damageSamples", doublesToArray(p.getDamageSamples()));

        // Also store computed values for quick reference (not loaded back — recomputed)
        obj.addProperty("_attackRange", p.getAttackRange());
        obj.addProperty("_cooldown", p.getAttackCooldownTicks());
        obj.addProperty("_speed", p.getMovementSpeed());
        obj.addProperty("_damage", p.getEstimatedDamage());
        obj.addProperty("_isRanged", p.isRanged());
        obj.addProperty("_threatLevel", p.getThreatLevel());
        obj.addProperty("_confidence", p.getOverallConfidence());

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(obj);
    }

    private static MobProfile profileFromJson(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String typeId = obj.get("entityTypeId").getAsString();
        MobProfile p = new MobProfile(typeId);

        p.setTotalHitsTaken(obj.get("totalHitsTaken").getAsInt());
        p.setTotalEncounters(obj.get("totalEncounters").getAsInt());
        p.setLastUpdatedTick(obj.get("lastUpdatedTick").getAsLong());
        p.setLastHitTick(obj.get("lastHitTick").getAsInt());
        p.setLongRangeHits(obj.get("longRangeHits").getAsInt());
        p.setShortRangeHits(obj.get("shortRangeHits").getAsInt());

        // Load raw samples
        arrayToDoubles(obj.getAsJsonArray("hitDistanceSamples"), p.getHitDistanceSamples());
        arrayToInts(obj.getAsJsonArray("hitIntervalSamples"), p.getHitIntervalSamples());
        arrayToDoubles(obj.getAsJsonArray("speedSamples"), p.getSpeedSamples());
        arrayToDoubles(obj.getAsJsonArray("damageSamples"), p.getDamageSamples());

        // Recompute derived values from loaded samples
        p.recomputeFromLoadedSamples();

        return p;
    }

    // ==================== JSON Helpers ====================

    private static JsonArray doublesToArray(List<Double> list) {
        JsonArray arr = new JsonArray();
        for (Double v : list) arr.add(Math.round(v * 1000.0) / 1000.0); // 3 decimal precision
        return arr;
    }

    private static JsonArray intsToArray(List<Integer> list) {
        JsonArray arr = new JsonArray();
        for (Integer v : list) arr.add(v);
        return arr;
    }

    private static void arrayToDoubles(JsonArray arr, List<Double> list) {
        if (arr == null) return;
        for (JsonElement e : arr) list.add(e.getAsDouble());
    }

    private static void arrayToInts(JsonArray arr, List<Integer> list) {
        if (arr == null) return;
        for (JsonElement e : arr) list.add(e.getAsInt());
    }

    // ==================== WebSocket Response ====================

    /**
     * Build a JSON response with all profiles for WebSocket query.
     */
    public static JsonObject toJsonResponse(MobProfileManager manager) {
        JsonObject resp = new JsonObject();
        JsonArray profiles = new JsonArray();
        for (MobProfile p : manager.getAllProfiles()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("entityType", p.entityTypeId);
            obj.addProperty("attackRange", Math.round(p.getAttackRange() * 100.0) / 100.0);
            obj.addProperty("attackCooldown", p.getAttackCooldownTicks());
            obj.addProperty("movementSpeed", Math.round(p.getMovementSpeed() * 1000.0) / 1000.0);
            obj.addProperty("estimatedDamage", Math.round(p.getEstimatedDamage() * 10.0) / 10.0);
            obj.addProperty("isRanged", p.isRanged());
            obj.addProperty("threatLevel", Math.round(p.getThreatLevel() * 100.0) / 100.0);
            obj.addProperty("confidence", Math.round(p.getOverallConfidence() * 100.0) / 100.0);
            obj.addProperty("hitsTaken", p.getTotalHitsTaken());
            obj.addProperty("encounters", p.getTotalEncounters());
            profiles.add(obj);
        }
        resp.add("profiles", profiles);
        resp.addProperty("count", profiles.size());
        return resp;
    }
}
