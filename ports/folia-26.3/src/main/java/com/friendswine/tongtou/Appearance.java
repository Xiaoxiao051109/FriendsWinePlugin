package com.friendswine.tongtou;

import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.Keyed;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;

public final class Appearance {
    private Appearance() {}
    public static Map<String, String> capture(Entity entity) {
        if (entity instanceof Player) throw new IllegalArgumentException("玩家不参与模型转换");
        Map<String, String> state = new HashMap<>();
        state.put("baby", Boolean.toString(entity instanceof Ageable age && !age.isAdult()
                || entity instanceof Zombie zombie && zombie.isBaby()));
        state.put("pose", entity.getPose().name().toLowerCase(Locale.ROOT));
        if (entity instanceof LivingEntity living) {
            var equipment = living.getEquipment();
            boolean armed = equipment != null && (!empty(equipment.getItemInMainHand()) || !empty(equipment.getItemInOffHand())
                    || Arrays.stream(equipment.getArmorContents()).anyMatch(item -> !empty(item)));
            state.put("equipment", armed ? "present" : "empty");
        } else state.put("equipment", "empty");
        Map<String, String> methods = Map.ofEntries(
                Map.entry("variant", "getVariant"), Map.entry("cat_type", "getCatType"),
                Map.entry("fox_type", "getFoxType"), Map.entry("rabbit_type", "getRabbitType"),
                Map.entry("color", "getColor"), Map.entry("style", "getStyle"),
                Map.entry("profession", "getProfession"), Map.entry("villager_type", "getVillagerType"),
                Map.entry("villager_level", "getVillagerLevel"), Map.entry("sheared", "isSheared"),
                Map.entry("sitting", "isSitting"), Map.entry("sleeping", "isSleeping"),
                Map.entry("boat_type", "getBoatType"), Map.entry("swimming", "isSwimming"), Map.entry("puff_state", "getPuffState"),
                Map.entry("angry", "isAngry"), Map.entry("tamed", "isTamed"),
                Map.entry("charged", "isPowered"), Map.entry("size", "getSize"),
                Map.entry("pattern", "getPattern"), Map.entry("body_color", "getBodyColor"),
                Map.entry("pattern_color", "getPatternColor"), Map.entry("collar_color", "getCollarColor"),
                Map.entry("carrying_chest", "isCarryingChest"),
                Map.entry("shivering", "isShivering"), Map.entry("derp", "isDerp"),
                Map.entry("small", "isSmall"), Map.entry("left_horn", "hasLeftHorn"), Map.entry("right_horn", "hasRightHorn"));
        methods.forEach((key, name) -> {
            try {
                Method method = entity.getClass().getMethod(name);
                Object value = method.invoke(entity);
                if (value instanceof Keyed keyed) state.put(key, keyed.getKey().toString());
                else if (value instanceof Enum<?> enumeration) state.put(key,
                        (key.equals("variant") ? "minecraft:" : "") + enumeration.name().toLowerCase(Locale.ROOT));
                else if (value instanceof Boolean || value instanceof Number) state.put(key, value.toString());
            } catch (ReflectiveOperationException ignored) {
                // 不同实体只暴露自身适用的外观属性。
            }
        });
        if(state.containsKey("boat_type"))state.computeIfPresent("boat_type",(key,value)->value.startsWith("minecraft:")?value.substring(10):value);
        if((entity instanceof Pig||entity instanceof Cow||entity instanceof Chicken)&&!hasMethod(entity,"getVariant"))
            state.put("variant","minecraft:temperate");
        if (entity instanceof Steerable steerable) state.put("saddled", Boolean.toString(steerable.hasSaddle()));
        state.put("special_name", "");
        if (entity instanceof AbstractHorse horse) state.put("saddled", Boolean.toString(!empty(horse.getInventory().getSaddle())));
        if (entity instanceof Horse horse) state.put("horse_armor", empty(horse.getInventory().getArmor()) ? "empty" : "present");
        if (entity instanceof Llama llama) state.put("decorated", Boolean.toString(!empty(llama.getInventory().getDecor())));
        if (entity instanceof Enderman enderman) {
            var carried = enderman.getCarriedBlock();
            state.put("carried_block", carried == null ? "empty" : carried.getAsString());
        }
        if (!state.containsKey("variant") && state.containsKey("cat_type")) {
            String type = state.get("cat_type");
            state.put("variant", type.contains(":") ? type : "minecraft:" + type);
        }
        String name = entity.getCustomName();
        if ("Dinnerbone".equals(name) || "Grumm".equals(name) || "jeb_".equals(name) || "Toast".equals(name)) state.put("special_name", name);
        return Map.copyOf(state);
    }
    private static boolean hasMethod(Entity entity,String name){
        try{entity.getClass().getMethod(name);return true;}catch(NoSuchMethodException absent){return false;}
    }
    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir(); }
}
