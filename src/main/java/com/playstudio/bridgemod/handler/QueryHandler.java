package com.playstudio.bridgemod.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.playstudio.bridgemod.util.BlockUtils;
import com.playstudio.bridgemod.util.EntityUtils;
import com.playstudio.bridgemod.websocket.BridgeWebSocketServer;
import com.playstudio.bridgemod.websocket.MessageHandler;
import com.playstudio.bridgemod.websocket.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.java_websocket.WebSocket;

import java.util.*;

/**
 * Handles all Phase 2 world query actions (read-only).
 * All queries execute on the MC client thread for thread safety.
 */
public class QueryHandler {

    private final BridgeWebSocketServer server;

    public QueryHandler(BridgeWebSocketServer server) {
        this.server = server;
    }

    /**
     * Register all query handlers with the message handler.
     */
    public void registerAll(MessageHandler messageHandler) {
        messageHandler.registerHandler("blockAt", this::handleBlockAt);
        messageHandler.registerHandler("findBlocks", this::handleFindBlocks);
        messageHandler.registerHandler("getEntities", this::handleGetEntities);
        messageHandler.registerHandler("getPlayers", this::handleGetPlayers);
        messageHandler.registerHandler("recipesFor", this::handleRecipesFor);
        messageHandler.registerHandler("getBiome", this::handleGetBiome);
        messageHandler.registerHandler("getInventory", this::handleGetInventory);
    }

    // --- blockAt ---

    private void handleBlockAt(WebSocket conn, String id, JsonObject params) {
        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();

        // Use ServerLevel for accurate results (ClientLevel only has client-loaded chunks)
        MinecraftServer mcServer = Minecraft.getInstance().getSingleplayerServer();
        if (mcServer != null) {
            mcServer.execute(() -> {
                ServerLevel level = mcServer.overworld();
                BlockPos pos = new BlockPos(x, y, z);
                JsonObject data = BlockUtils.getBlockInfo(level, pos);
                server.sendResponse(conn, id, true, data, null);
            });
        } else {
            // Fallback to ClientLevel for dedicated server connections
            Minecraft.getInstance().execute(() -> {
                ClientLevel level = Minecraft.getInstance().level;
                if (level == null) {
                    server.sendResponse(conn, id, false, null, "No world loaded");
                    return;
                }
                BlockPos pos = new BlockPos(x, y, z);
                JsonObject data = BlockUtils.getBlockInfo(level, pos);
                server.sendResponse(conn, id, true, data, null);
            });
        }
    }

    // --- findBlocks ---

    /**
     * findBlocks: Search for blocks by name within a radius.
     * params: {blockNames: [...], center?: {x,y,z}, maxDistance?/radius?: int, count?/maxResults?: int}
     * If center is omitted, defaults to local player position.
     */
    private void handleFindBlocks(WebSocket conn, String id, JsonObject params) {
        // Support both "blockNames" and "blockIds" param names
        JsonArray blockNamesArr = params.has("blockNames") ? params.getAsJsonArray("blockNames")
                : params.has("blockIds") ? params.getAsJsonArray("blockIds") : null;
        if (blockNamesArr == null || blockNamesArr.isEmpty()) {
            server.sendResponse(conn, id, false, null, "Missing 'blockNames' array parameter");
            return;
        }
        Set<String> targetNames = new HashSet<>();
        for (var elem : blockNamesArr) {
            targetNames.add(Protocol.stripNamespace(elem.getAsString()));
        }

        // Support "radius" as alias for "maxDistance"
        int maxDistance;
        if (params.has("maxDistance")) maxDistance = params.get("maxDistance").getAsInt();
        else if (params.has("radius")) maxDistance = params.get("radius").getAsInt();
        else maxDistance = 64;

        // Support "maxResults" as alias for "count"
        int count;
        if (params.has("count")) count = params.get("count").getAsInt();
        else if (params.has("maxResults")) count = params.get("maxResults").getAsInt();
        else count = 100;

        // Parse optional center position
        final Integer centerX, centerY, centerZ;
        if (params.has("center") && params.get("center").isJsonObject()) {
            JsonObject c = params.getAsJsonObject("center");
            centerX = c.get("x").getAsInt();
            centerY = c.get("y").getAsInt();
            centerZ = c.get("z").getAsInt();
        } else if (params.has("x") && params.has("y") && params.has("z")) {
            centerX = params.get("x").getAsInt();
            centerY = params.get("y").getAsInt();
            centerZ = params.get("z").getAsInt();
        } else {
            centerX = null;
            centerY = null;
            centerZ = null;
        }

        // Use ServerLevel for accurate results
        MinecraftServer mcServer = Minecraft.getInstance().getSingleplayerServer();
        if (mcServer != null) {
            mcServer.execute(() -> {
                ServerLevel level = mcServer.overworld();

                // Determine center: explicit > player position
                BlockPos center;
                if (centerX != null) {
                    center = new BlockPos(centerX, centerY, centerZ);
                } else {
                    LocalPlayer player = Minecraft.getInstance().player;
                    if (player != null) {
                        center = player.blockPosition();
                    } else {
                        center = new BlockPos(0, 64, 0);
                    }
                }

                List<BlockPos> found = searchBlocks(level, center, targetNames, maxDistance, count);

                JsonObject data = new JsonObject();
                JsonArray blocks = new JsonArray();
                for (BlockPos p : found) {
                    blocks.add(Protocol.vec3(p.getX(), p.getY(), p.getZ()));
                }
                data.add("blocks", blocks);
                server.sendResponse(conn, id, true, data, null);
            });
        } else {
            // Fallback to ClientLevel
            Minecraft.getInstance().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer player = mc.player;
                ClientLevel level = mc.level;
                if (level == null) {
                    server.sendResponse(conn, id, false, null, "No world loaded");
                    return;
                }

                BlockPos center;
                if (centerX != null) {
                    center = new BlockPos(centerX, centerY, centerZ);
                } else if (player != null) {
                    center = player.blockPosition();
                } else {
                    center = new BlockPos(0, 64, 0);
                }

                List<BlockPos> found = searchBlocks(level, center, targetNames, maxDistance, count);

                JsonObject data = new JsonObject();
                JsonArray blocks = new JsonArray();
                for (BlockPos p : found) {
                    blocks.add(Protocol.vec3(p.getX(), p.getY(), p.getZ()));
                }
                data.add("blocks", blocks);
                server.sendResponse(conn, id, true, data, null);
            });
        }
    }

    /**
     * Shared block search logic for both ServerLevel and ClientLevel.
     */
    private List<BlockPos> searchBlocks(net.minecraft.world.level.Level level, BlockPos center,
                                        Set<String> targetNames, int maxDistance, int count) {
        int minX = center.getX() - maxDistance;
        int maxX = center.getX() + maxDistance;
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - maxDistance);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + maxDistance);
        int minZ = center.getZ() - maxDistance;
        int maxZ = center.getZ() + maxDistance;

        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!level.hasChunkAt(mutable.set(bx, 0, bz))) continue;
                for (int by = minY; by <= maxY; by++) {
                    mutable.set(bx, by, bz);
                    BlockState state = level.getBlockState(mutable);
                    String name = BlockUtils.getBlockName(state);
                    if (targetNames.contains(name)) {
                        found.add(mutable.immutable());
                    }
                }
            }
        }

        found.sort(Comparator.comparingDouble(p -> p.distSqr(center)));

        if (found.size() > count) {
            found = found.subList(0, count);
        }
        return found;
    }

    // --- getEntities ---

    private void handleGetEntities(WebSocket conn, String id, JsonObject params) {
        int maxDistance = params.has("maxDistance") ? params.get("maxDistance").getAsInt() : 32;

        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            if (player == null || level == null) {
                server.sendResponse(conn, id, false, null, "No world loaded");
                return;
            }

            AABB searchBox = player.getBoundingBox().inflate(maxDistance);
            List<Entity> entities = level.getEntitiesOfClass(Entity.class, searchBox);

            JsonObject data = new JsonObject();
            JsonArray entArray = new JsonArray();
            for (Entity entity : entities) {
                if (entity == player) continue; // skip self
                entArray.add(EntityUtils.buildEntityJson(entity));
            }
            data.add("entities", entArray);
            server.sendResponse(conn, id, true, data, null);
        });
    }

    // --- getPlayers ---

    private void handleGetPlayers(WebSocket conn, String id, JsonObject params) {
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            ClientLevel level = mc.level;
            if (player == null || level == null || mc.getConnection() == null) {
                server.sendResponse(conn, id, false, null, "No world loaded");
                return;
            }

            JsonObject data = new JsonObject();
            JsonArray playersArray = new JsonArray();

            var playerInfos = mc.getConnection().getOnlinePlayers();
            for (var info : playerInfos) {
                JsonObject pObj = new JsonObject();
                String name = info.getProfile().getName();
                String uuid = info.getProfile().getId().toString();
                pObj.addProperty("name", name);
                pObj.addProperty("uuid", uuid);

                Player remotePlayer = level.getPlayerByUUID(info.getProfile().getId());
                if (remotePlayer != null) {
                    pObj.add("position", Protocol.vec3(
                            remotePlayer.getX(), remotePlayer.getY(), remotePlayer.getZ()));
                    pObj.addProperty("isInRange", true);
                } else {
                    pObj.add("position", JsonNull.INSTANCE);
                    pObj.addProperty("isInRange", false);
                }

                playersArray.add(pObj);
            }

            data.add("players", playersArray);
            server.sendResponse(conn, id, true, data, null);
        });
    }

    // --- recipesFor ---

    private void handleRecipesFor(WebSocket conn, String id, JsonObject params) {
        if (!params.has("itemName")) {
            server.sendResponse(conn, id, false, null, "Missing 'itemName' parameter");
            return;
        }
        String itemName = Protocol.stripNamespace(params.get("itemName").getAsString());

        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                server.sendResponse(conn, id, false, null, "No world loaded");
                return;
            }

            ResourceLocation targetRL = new ResourceLocation("minecraft", itemName);
            var targetItem = BuiltInRegistries.ITEM.get(targetRL);
            if (targetItem == Items.AIR) {
                server.sendResponse(conn, id, false, null, "Unknown item: " + itemName);
                return;
            }

            var recipeManager = level.getRecipeManager();
            var allCraftingRecipes = recipeManager.getAllRecipesFor(RecipeType.CRAFTING);

            JsonObject data = new JsonObject();
            JsonArray recipesArray = new JsonArray();

            for (CraftingRecipe recipe : allCraftingRecipes) {
                ItemStack resultStack = recipe.getResultItem(level.registryAccess());
                if (!resultStack.is(targetItem)) continue;

                JsonObject recipeObj = new JsonObject();

                // Aggregate ingredients by name
                Map<String, Integer> ingredientCounts = new LinkedHashMap<>();
                for (var ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    ItemStack[] stacks = ingredient.getItems();
                    if (stacks.length > 0) {
                        String ingName = BuiltInRegistries.ITEM.getKey(stacks[0].getItem()).getPath();
                        ingredientCounts.merge(ingName, 1, Integer::sum);
                    }
                }

                JsonArray ingredients = new JsonArray();
                for (var entry : ingredientCounts.entrySet()) {
                    JsonObject ing = new JsonObject();
                    ing.addProperty("name", entry.getKey());
                    ing.addProperty("count", entry.getValue());
                    ingredients.add(ing);
                }
                recipeObj.add("ingredients", ingredients);

                // Result
                JsonObject result = new JsonObject();
                result.addProperty("name", BuiltInRegistries.ITEM.getKey(resultStack.getItem()).getPath());
                result.addProperty("count", resultStack.getCount());
                recipeObj.add("result", result);

                // requiresCraftingTable: needs 3x3 grid but not 2x2
                boolean needs3x3 = recipe.canCraftInDimensions(3, 3)
                        && !recipe.canCraftInDimensions(2, 2);
                recipeObj.addProperty("requiresCraftingTable", needs3x3);

                recipesArray.add(recipeObj);
            }

            data.add("recipes", recipesArray);
            server.sendResponse(conn, id, true, data, null);
        });
    }

    // --- getBiome ---

    private void handleGetBiome(WebSocket conn, String id, JsonObject params) {
        int x = params.get("x").getAsInt();
        int y = params.get("y").getAsInt();
        int z = params.get("z").getAsInt();

        Minecraft.getInstance().execute(() -> {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                server.sendResponse(conn, id, false, null, "No world loaded");
                return;
            }

            BlockPos pos = new BlockPos(x, y, z);
            var biomeHolder = level.getBiome(pos);
            String biomeName = biomeHolder.unwrapKey()
                    .map(key -> key.location().getPath())
                    .orElse("unknown");

            JsonObject data = new JsonObject();
            data.addProperty("name", biomeName);
            server.sendResponse(conn, id, true, data, null);
        });
    }

    // --- getInventory ---

    private void handleGetInventory(WebSocket conn, String id, JsonObject params) {
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) {
                server.sendResponse(conn, id, false, null, "No player loaded");
                return;
            }

            JsonObject data = new JsonObject();
            JsonArray items = new JsonArray();
            int emptyCount = 0;

            var menu = player.inventoryMenu;
            for (int i = 0; i < menu.slots.size(); i++) {
                ItemStack stack = menu.slots.get(i).getItem();
                if (stack.isEmpty()) {
                    // Count empty slots in main inventory (9-35) + hotbar (36-44)
                    if (i >= 9 && i <= 44) emptyCount++;
                    continue;
                }

                JsonObject itemObj = new JsonObject();
                itemObj.addProperty("slot", i);
                String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
                itemObj.addProperty("name", name);
                itemObj.addProperty("count", stack.getCount());

                if (stack.isDamageableItem()) {
                    int maxDur = stack.getMaxDamage();
                    int currentDur = maxDur - stack.getDamageValue();
                    itemObj.addProperty("durability", currentDur);
                    itemObj.addProperty("maxDurability", maxDur);
                }

                items.add(itemObj);
            }

            data.add("items", items);
            data.addProperty("emptySlotCount", emptyCount);

            JsonObject equipment = new JsonObject();
            equipment.addProperty("head", 5);
            equipment.addProperty("chest", 6);
            equipment.addProperty("legs", 7);
            equipment.addProperty("feet", 8);
            equipment.addProperty("offhand", 45);
            data.add("equipment", equipment);

            server.sendResponse(conn, id, true, data, null);
        });
    }
}
