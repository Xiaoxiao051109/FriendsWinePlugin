package com.friendswine.tongtou;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftEngineCategoryTest {
    @Test void builtinCategoryIsVisibleAndContainsExactlyRequestedItems() throws Exception {
        var source = BuiltinAssets.bundled();
        byte[] bytes = BuiltinAssets.read(source, "ce/configuration/categories.yml", 10000);
        var yaml = new YamlConfiguration();
        yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        String prefix = "categories.tongtou:main.";
        assertTrue(yaml.getString(prefix + "name").contains("通透模式"));
        assertFalse(yaml.getBoolean(prefix + "hidden"));
        assertEquals("tongtou:doll", yaml.getString(prefix + "icon"));
        assertEquals(List.of("tongtou:mode", "tongtou:doll", "tongtou:remote"), yaml.getStringList(prefix + "list"));
        var items = new YamlConfiguration();
        items.loadFromString(new String(BuiltinAssets.read(source, "ce/configuration/items.yml", 10000), StandardCharsets.UTF_8));
        assertEquals(java.util.Set.of("tongtou:mode","tongtou:doll","tongtou:remote"),items.getConfigurationSection("items").getKeys(false));
        assertEquals("tt_doll",items.getString("items.tongtou:doll.behavior.furniture.variants.ground.blueprint"));
        assertTrue(items.getList("items.tongtou:doll.behavior.furniture.variants.ground.elements").isEmpty());
    }
}
