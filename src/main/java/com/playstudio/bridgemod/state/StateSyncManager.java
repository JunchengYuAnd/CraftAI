package com.playstudio.bridgemod.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.playstudio.bridgemod.websocket.BridgeWebSocketServer;
import com.playstudio.bridgemod.websocket.Protocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Pushes player state to all handshaked WebSocket connections every 2 ticks (100ms).
 * Must be registered on MinecraftForge.EVENT_BUS.
 */
public class StateSyncManager {

    private static final int SYNC_INTERVAL = 2; // every 2 ticks = 100ms
    private int tickCounter = 0;
    private final BridgeWebSocketServer server;

    public StateSyncManager(BridgeWebSocketServer server) {
        this.server = server;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!server.hasHandshakedConnections()) return;

        tickCounter++;
        if (tickCounter < SYNC_INTERVAL) return;
        tickCounter = 0;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        JsonObject data = buildSnapshot(mc);
        server.broadcastToHandshaked(Protocol.stateSync(data));
    }

    private JsonObject buildSnapshot(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        JsonObject data = new JsonObject();

        // --- Position & Movement ---
        Vec3 pos = player.position();
        data.add("position", Protocol.vec3(pos.x, pos.y, pos.z));

        Vec3 vel = player.getDeltaMovement();
        data.add("velocity", Protocol.vec3(vel.x, vel.y, vel.z));

        data.addProperty("yaw", player.getYRot());
        data.addProperty("pitch", player.getXRot());
        data.addProperty("onGround", player.onGround());
        data.addProperty("isInWater", player.isInWater());
        data.addProperty("isInLava", player.isInLava());
        data.addProperty("height", (double) player.getBbHeight());

        // --- Survival Stats ---
        data.addProperty("health", player.getHealth());
        FoodData food = player.getFoodData();
        data.addProperty("food", food.getFoodLevel());
        data.addProperty("saturation", food.getSaturationLevel());
        data.addProperty("oxygenLevel", player.getAirSupply());
        data.addProperty("isSleeping", player.isSleeping());

        // --- Game Info ---
        data.addProperty("gameMode", getGameModeName(mc));
        data.addProperty("dimension", getDimensionName(level));
        data.addProperty("timeOfDay", level.getDayTime() % 24000L);
        data.addProperty("rainState", level.getRainLevel(1.0f));
        data.addProperty("thunderState", level.getThunderLevel(1.0f));

        // --- Held Item ---
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            String name = getItemName(mainHand);
            int count = mainHand.getCount();
            int slot = player.getInventory().selected;
            int maxDur = mainHand.isDamageableItem() ? mainHand.getMaxDamage() : 0;
            int dur = mainHand.isDamageableItem() ? mainHand.getMaxDamage() - mainHand.getDamageValue() : 0;
            data.add("heldItem", Protocol.item(name, count, slot, dur, maxDur));
        } else {
            data.add("heldItem", JsonNull.INSTANCE);
        }

        // --- Inventory Summary ---
        int usedSlots = 0;
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            if (!player.getInventory().items.get(i).isEmpty()) usedSlots++;
        }
        data.addProperty("inventoryUsedSlots", usedSlots);
        data.addProperty("inventoryTotalSlots", 36);

        // --- Equipment ---
        JsonObject equipment = new JsonObject();
        equipment.add("head", serializeEquipSlot(player, EquipmentSlot.HEAD));
        equipment.add("chest", serializeEquipSlot(player, EquipmentSlot.CHEST));
        equipment.add("legs", serializeEquipSlot(player, EquipmentSlot.LEGS));
        equipment.add("feet", serializeEquipSlot(player, EquipmentSlot.FEET));
        equipment.add("offhand", serializeEquipSlot(player, EquipmentSlot.OFFHAND));
        data.add("equipment", equipment);

        return data;
    }

    private static String getGameModeName(Minecraft mc) {
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (gameMode == null) return "unknown";
        GameType gt = gameMode.getPlayerMode();
        return gt.getName();
    }

    private static String getDimensionName(ClientLevel level) {
        ResourceKey<Level> dim = level.dimension();
        if (dim == Level.OVERWORLD) return "overworld";
        if (dim == Level.NETHER) return "the_nether";
        if (dim == Level.END) return "the_end";
        return dim.location().toString();
    }

    private static String getItemName(ItemStack stack) {
        return stack.getItem().builtInRegistryHolder().key().location().getPath();
    }

    private static JsonElement serializeEquipSlot(LocalPlayer player, EquipmentSlot slot) {
        ItemStack stack = player.getItemBySlot(slot);
        if (stack.isEmpty()) return JsonNull.INSTANCE;

        String name = getItemName(stack);
        int durability = stack.isDamageableItem()
                ? stack.getMaxDamage() - stack.getDamageValue()
                : -1;

        return Protocol.equipmentSlot(name, durability);
    }
}
