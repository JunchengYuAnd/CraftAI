package com.playstudio.bridgemod.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.playstudio.bridgemod.websocket.Protocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block property extraction utilities for world queries.
 */
public class BlockUtils {

    // Block drops that differ from block.asItem() (client has no ServerLevel for loot tables)
    private static final Map<String, List<String>> BLOCK_DROP_OVERRIDES = new HashMap<>();

    static {
        // Ores → raw materials / gems
        BLOCK_DROP_OVERRIDES.put("stone", List.of("cobblestone"));
        BLOCK_DROP_OVERRIDES.put("coal_ore", List.of("coal"));
        BLOCK_DROP_OVERRIDES.put("deepslate_coal_ore", List.of("coal"));
        BLOCK_DROP_OVERRIDES.put("iron_ore", List.of("raw_iron"));
        BLOCK_DROP_OVERRIDES.put("deepslate_iron_ore", List.of("raw_iron"));
        BLOCK_DROP_OVERRIDES.put("copper_ore", List.of("raw_copper"));
        BLOCK_DROP_OVERRIDES.put("deepslate_copper_ore", List.of("raw_copper"));
        BLOCK_DROP_OVERRIDES.put("gold_ore", List.of("raw_gold"));
        BLOCK_DROP_OVERRIDES.put("deepslate_gold_ore", List.of("raw_gold"));
        BLOCK_DROP_OVERRIDES.put("diamond_ore", List.of("diamond"));
        BLOCK_DROP_OVERRIDES.put("deepslate_diamond_ore", List.of("diamond"));
        BLOCK_DROP_OVERRIDES.put("emerald_ore", List.of("emerald"));
        BLOCK_DROP_OVERRIDES.put("deepslate_emerald_ore", List.of("emerald"));
        BLOCK_DROP_OVERRIDES.put("lapis_ore", List.of("lapis_lazuli"));
        BLOCK_DROP_OVERRIDES.put("deepslate_lapis_ore", List.of("lapis_lazuli"));
        BLOCK_DROP_OVERRIDES.put("redstone_ore", List.of("redstone"));
        BLOCK_DROP_OVERRIDES.put("deepslate_redstone_ore", List.of("redstone"));
        BLOCK_DROP_OVERRIDES.put("nether_quartz_ore", List.of("quartz"));
        BLOCK_DROP_OVERRIDES.put("nether_gold_ore", List.of("gold_nugget"));
        // Blocks that drop different items
        BLOCK_DROP_OVERRIDES.put("grass_block", List.of("dirt"));
        BLOCK_DROP_OVERRIDES.put("bookshelf", List.of("book"));
        BLOCK_DROP_OVERRIDES.put("clay", List.of("clay_ball"));
        BLOCK_DROP_OVERRIDES.put("snow_block", List.of("snowball"));
        BLOCK_DROP_OVERRIDES.put("melon", List.of("melon_slice"));
        BLOCK_DROP_OVERRIDES.put("glowstone", List.of("glowstone_dust"));
        // Blocks that drop nothing
        BLOCK_DROP_OVERRIDES.put("glass", List.of());
        BLOCK_DROP_OVERRIDES.put("glass_pane", List.of());
        BLOCK_DROP_OVERRIDES.put("ice", List.of());
        BLOCK_DROP_OVERRIDES.put("short_grass", List.of());
        BLOCK_DROP_OVERRIDES.put("tall_grass", List.of());
    }

    /**
     * Get the registry name (path only) of a block.
     * e.g. "stone", "oak_log", "diamond_ore"
     */
    public static String getBlockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /**
     * Build the full blockAt response JSON for a given position.
     */
    public static JsonObject getBlockInfo(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        String name = getBlockName(state);

        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("stateId", Block.getId(state));
        result.add("position", Protocol.vec3(pos.getX(), pos.getY(), pos.getZ()));

        float hardness = state.getDestroySpeed(level, pos);
        result.addProperty("diggable", hardness >= 0);
        result.addProperty("hardness", hardness);

        boolean hasCollision = !state.getCollisionShape(level, pos).isEmpty();
        result.addProperty("boundingBox", hasCollision ? "block" : "empty");

        result.addProperty("transparent", !state.canOcclude());

        // Drops
        JsonArray drops = new JsonArray();
        if (BLOCK_DROP_OVERRIDES.containsKey(name)) {
            for (String dropName : BLOCK_DROP_OVERRIDES.get(name)) {
                drops.add(dropName);
            }
        } else {
            var item = block.asItem();
            if (item != null && item != Items.AIR) {
                String itemName = BuiltInRegistries.ITEM.getKey(item).getPath();
                drops.add(itemName);
            }
        }
        result.add("drops", drops);

        return result;
    }
}
