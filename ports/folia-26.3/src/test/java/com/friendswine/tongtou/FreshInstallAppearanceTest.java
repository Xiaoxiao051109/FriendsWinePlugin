package com.friendswine.tongtou;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import org.bukkit.NamespacedKey;
import org.bukkit.DyeColor;
import org.bukkit.entity.*;
import java.util.Map;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Pose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FreshInstallAppearanceTest {
    @TempDir Path temporary;
    private Pig pig(String climate, boolean baby, boolean saddled) {
        Pig.Variant variant = new Pig.Variant() {
            @Override public NamespacedKey getKey() { return NamespacedKey.minecraft(climate); }
        };
        return (Pig) Proxy.newProxyInstance(Pig.class.getClassLoader(), new Class<?>[]{Pig.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "isAdult" -> !baby;
                case "getPose" -> Pose.STANDING;
                case "getEquipment", "getCustomName" -> null;
                case "getVariant" -> variant;
                case "hasSaddle" -> saddled;
                case "toString" -> "测试猪";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("不应读取非外观字段：" + method.getName());
            };
        });
    }
    private ModelCatalog installCatalog() throws Exception {
        BuiltinAssets.install(BuiltinAssets.bundled(), temporary.resolve("TongtouMode"), temporary.resolve("BetterModel"), null);
        return ModelCatalog.load(temporary.resolve("TongtouMode/catalog.json"));
    }
    @Test void ordinaryPigMatchesFreshlyInstalledCatalog() throws Exception {
        var catalog = installCatalog();
        var state = Appearance.capture(pig("temperate", false, false));
        assertEquals("false", state.get("saddled"), "26.3猪使用hasSaddle，而不是不存在的isSaddled");
        assertEquals("tt_pig_temperate", catalog.resolve("minecraft:pig", state).orElseThrow().model());
    }
    @Test void allThreeClimatesAndBothAgesMatchRealPigInterface() throws Exception {
        var catalog = installCatalog();
        for (String climate : new String[]{"temperate", "cold", "warm"}) {
            for (boolean baby : new boolean[]{false, true}) {
                var state = Appearance.capture(pig(climate, baby, false));
                assertEquals("tt_pig_" + climate + (baby ? "_baby" : ""),
                        catalog.resolve("minecraft:pig", state).orElseThrow().model());
            }
        }
    }
    @Test void saddledPigIsNotDisguisedAsUnsaddled() throws Exception {
        var catalog = installCatalog();
        assertTrue(catalog.resolve("minecraft:pig", Appearance.capture(pig("temperate", false, true))).isEmpty());
    }
    private <T extends Entity> T animal(Class<T> type, Map<String, Object> fields) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (fields.containsKey(method.getName())) return fields.get(method.getName());
            return switch (method.getName()) {
                case "isAdult" -> true;
                case "isBaby", "isSwimming" -> false;
                case "getPose" -> Pose.STANDING;
                case "getEquipment", "getCustomName" -> null;
                case "toString" -> "测试动物";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new AssertionError("不应读取非外观字段：" + method.getName());
            };
        }));
    }
    @Test void ordinaryCowChickenAndSheepAlsoMatchFreshCatalog() throws Exception {
        var catalog = installCatalog();
        Cow.Variant cowVariant = new Cow.Variant() {
            @Override public NamespacedKey getKey() { return NamespacedKey.minecraft("temperate"); }
        };
        Chicken.Variant chickenVariant = new Chicken.Variant() {
            @Override public NamespacedKey getKey() { return NamespacedKey.minecraft("temperate"); }
        };
        var cow = animal(Cow.class, Map.of("getVariant", cowVariant));
        var chicken = animal(Chicken.class, Map.of("getVariant", chickenVariant));
        var sheep = animal(Sheep.class, Map.of("getColor", DyeColor.WHITE, "isSheared", false));
        assertEquals("tt_cow_temperate", catalog.resolve("minecraft:cow", Appearance.capture(cow)).orElseThrow().model());
        assertEquals("tt_chicken_temperate", catalog.resolve("minecraft:chicken", Appearance.capture(chicken)).orElseThrow().model());
        assertEquals("tt_sheep_white_wool", catalog.resolve("minecraft:sheep", Appearance.capture(sheep)).orElseThrow().model());
    }
    @Test void striderUsesSameActualSaddleApi() throws Exception {
        var catalog = installCatalog();
        var strider = animal(Strider.class, Map.of("hasSaddle", false, "isShivering", false));
        var state = Appearance.capture(strider);
        assertEquals("false", state.get("saddled"));
        assertEquals("tt_strider", catalog.resolve("minecraft:strider", state).orElseThrow().model());
    }

    @Test void slimeAndMagmaCubeMatchActualNaturalSizesWithoutNbtOffByOne() throws Exception {
        var catalog=installCatalog();
        for(int size:new int[]{1,2,4})for(Class<? extends Entity> type:java.util.List.<Class<? extends Entity>>of(Slime.class,MagmaCube.class)){
            var mob=animal(type,Map.of("getSize",size));var state=Appearance.capture(mob);
            assertEquals(Integer.toString(size),state.get("size"));
            String name=type==Slime.class?"slime":"magma_cube";
            assertEquals("tt_"+name+"_size_"+size,catalog.resolve("minecraft:"+name,state).orElseThrow().model());
        }
        var unsupported=Appearance.capture(animal(Slime.class,Map.of("getSize",3)));
        assertTrue(catalog.resolve("minecraft:slime",unsupported).isEmpty(),"不能把实际3号大小冒充2号或4号");
    }
    @Test void adultAndBabyDrownedUseTheirOwnGeometryAndOverlay() throws Exception {
        var catalog=installCatalog();
        for(boolean baby:new boolean[]{false,true}){
            var state=Appearance.capture(animal(Drowned.class,Map.of("isBaby",baby,"isSwimming",false)));
            assertEquals("tt_drowned"+(baby?"_baby":""),catalog.resolve("minecraft:drowned",state).orElseThrow().model());
            var armed=new java.util.HashMap<>(state);armed.put("equipment","present");
            assertTrue(catalog.resolve("minecraft:drowned",armed).isEmpty());
        }
    }
    @Test void standingPoseDoesNotHideActualDrownedSwimmingState() throws Exception {
        var catalog=installCatalog();
        var state=Appearance.capture(animal(Drowned.class,Map.of("isBaby",false,"isSwimming",true)));
        assertEquals("standing",state.get("pose"));assertEquals("true",state.get("swimming"));
        assertTrue(catalog.resolve("minecraft:drowned",state).isEmpty(),"必须读取游泳标志，不把standing误当未游泳");
    }
    @Test void otherMissingBasicMobsAndPufferStatesHaveDedicatedModels() throws Exception {
        var catalog=installCatalog();
        assertEquals("tt_cave_spider",catalog.resolve("minecraft:cave_spider",Appearance.capture(animal(CaveSpider.class,Map.of()))).orElseThrow().model());
        assertEquals("tt_wandering_trader",catalog.resolve("minecraft:wandering_trader",Appearance.capture(animal(WanderingTrader.class,Map.of()))).orElseThrow().model());
        String[] names={"small","medium","big"};
        for(int state=0;state<3;state++){
            var appearance=Appearance.capture(animal(PufferFish.class,Map.of("getPuffState",state)));
            assertEquals("tt_pufferfish_"+names[state],catalog.resolve("minecraft:pufferfish",appearance).orElseThrow().model());
        }
    }
}
