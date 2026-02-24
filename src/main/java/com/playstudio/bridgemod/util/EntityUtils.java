package com.playstudio.bridgemod.util;

import com.google.gson.JsonObject;
import com.playstudio.bridgemod.websocket.Protocol;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;

/**
 * Entity classification and metadata extraction utilities.
 */
public class EntityUtils {

    /**
     * Classify an entity into a type string for the protocol.
     * "hostile", "animal", "mob", "player", "other"
     */
    public static String classifyEntity(Entity entity) {
        if (entity instanceof Player) return "player";
        if (entity instanceof Monster) return "hostile";
        if (entity instanceof Animal) return "animal";
        if (entity instanceof AbstractVillager) return "mob";
        String name = getEntityName(entity);
        if ("iron_golem".equals(name) || "snow_golem".equals(name)) return "mob";
        return "other";
    }

    /**
     * Get the registry name (path) of an entity type.
     * e.g. "zombie", "villager", "iron_golem"
     */
    public static String getEntityName(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
    }

    /**
     * Build a full entity JSON object for protocol responses.
     */
    public static JsonObject buildEntityJson(Entity entity) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", entity.getId());
        obj.addProperty("name", getEntityName(entity));
        obj.addProperty("type", classifyEntity(entity));
        obj.add("position", Protocol.vec3(entity.getX(), entity.getY(), entity.getZ()));

        if (entity instanceof LivingEntity living) {
            obj.addProperty("health", living.getHealth());
        }

        JsonObject metadata = new JsonObject();

        if (entity instanceof AgeableMob ageable) {
            metadata.addProperty("isBaby", ageable.isBaby());
        }

        if (entity instanceof Villager villager) {
            var villagerData = villager.getVillagerData();
            String profession = BuiltInRegistries.VILLAGER_PROFESSION
                    .getKey(villagerData.getProfession()).getPath();
            metadata.addProperty("profession", profession);
            metadata.addProperty("level", villagerData.getLevel());
        }

        obj.add("metadata", metadata);
        return obj;
    }
}
