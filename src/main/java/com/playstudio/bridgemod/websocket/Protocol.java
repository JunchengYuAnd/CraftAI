package com.playstudio.bridgemod.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Helper methods for building WebSocket protocol JSON messages.
 */
public class Protocol {

    /**
     * Build a response message.
     */
    public static String response(String id, boolean success, JsonObject data, String error) {
        JsonObject json = new JsonObject();
        if (id != null) json.addProperty("id", id);
        json.addProperty("type", "response");
        json.addProperty("success", success);
        if (data != null) {
            json.add("data", data);
        } else {
            json.add("data", new JsonObject());
        }
        if (error != null) {
            json.addProperty("error", error);
        }
        return json.toString();
    }

    /**
     * Build a state_sync message.
     */
    public static String stateSync(JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "state_sync");
        json.add("data", data);
        return json.toString();
    }

    /**
     * Build an event message.
     */
    public static String event(String eventName, JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "event");
        json.addProperty("event", eventName);
        json.add("data", data);
        return json.toString();
    }

    /**
     * Strip namespace prefix from a Minecraft resource ID.
     * e.g. "minecraft:oak_log" → "oak_log", "oak_log" → "oak_log"
     * Handles any namespace (e.g. "modname:custom_block" → "custom_block").
     */
    public static String stripNamespace(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /**
     * Build a position/vector object {x, y, z}.
     */
    public static JsonObject vec3(double x, double y, double z) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        return obj;
    }

    /**
     * Build an item object. Returns JsonNull if the item is empty/null.
     */
    public static JsonElement item(String name, int count, int slot,
                                   int durability, int maxDurability) {
        if (name == null) return JsonNull.INSTANCE;
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        obj.addProperty("count", count);
        obj.addProperty("slot", slot);
        if (maxDurability > 0) {
            obj.addProperty("maxDurability", maxDurability);
            obj.addProperty("durability", durability);
        }
        return obj;
    }

    /**
     * Build an equipment slot object. Returns JsonNull if empty.
     */
    public static JsonElement equipmentSlot(String name, int durability) {
        if (name == null) return JsonNull.INSTANCE;
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        if (durability >= 0) {
            obj.addProperty("durability", durability);
        }
        return obj;
    }
}
