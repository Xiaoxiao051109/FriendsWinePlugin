package com.friendswine.tongtou;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackagedJarTest {
    @Test void deliveredJarHasAllModelsCatalogConfigAndNoClientOrBlockCode() throws Exception {
        Path jar = Path.of(System.getProperty("tongtou.jar"));
        assertTrue(Files.size(jar) > 1_000_000, "不能交付空资源的几十KB插件");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var index = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("bundled/index.json")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            int models = 0;
            for (var value : index.getAsJsonArray("files")) {
                JsonObject record = value.getAsJsonObject();
                String path = record.get("path").getAsString();
                var entry = zip.getEntry("bundled/" + path);
                assertNotNull(entry, path);
                byte[] bytes = zip.getInputStream(entry).readAllBytes();
                assertEquals(record.get("size").getAsInt(), bytes.length, path);
                assertEquals(record.get("sha256").getAsString(), BuiltinAssets.hash(bytes), path);
                if (path.startsWith("models/")) models++;
            }
            assertEquals(823, models);
            assertNotNull(zip.getEntry("bundled/models/tt_doll.bbmodel"));
            assertNotNull(zip.getEntry("bundled/music.json"));
            assertNotNull(zip.getEntry("bundled/feature-models.json"));
            assertEquals(56, zip.stream().filter(e->e.getName().matches("bundled/ce/resourcepack/assets/tongtou/sounds/party/music_[0-9]+[.]ogg")).count());
            assertNotNull(zip.getEntry("bundled/catalog.json"));
            assertNotNull(zip.getEntry("bundled/ce/configuration/items.yml"));
            assertNotNull(zip.getEntry("bundled/ce/configuration/categories.yml"));
            assertNotNull(zip.getEntry("config.yml"));
            var descriptor=new org.bukkit.configuration.file.YamlConfiguration();
            descriptor.loadFromString(new String(zip.getInputStream(zip.getEntry("plugin.yml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("FriendsWinePlugin",descriptor.getString("name"));assertEquals("1.1.0",descriptor.getString("version"));
            assertEquals("com.friendswine.tongtou.FriendsWinePlugin",descriptor.getString("main"));
            assertEquals("1.21",descriptor.getString("api-version"));
            assertNotNull(zip.getEntry("com/friendswine/tongtou/ModernModelBackend.class"));
            assertNotNull(zip.getEntry("com/friendswine/tongtou/LegacyModelBackend.class"));
            assertNotNull(zip.getEntry("bundled/compat/classic-models.zip"));
            for(var entry:zip.stream().filter(e->e.getName().endsWith(".class")).toList()){
                byte[] bytes=zip.getInputStream(entry).readAllBytes();
                int major=(Byte.toUnsignedInt(bytes[6])<<8)|Byte.toUnsignedInt(bytes[7]);
                assertTrue(major<=65,"公共JAR应为Java21字节码："+entry.getName());
            }
            assertNotNull(zip.getEntry("com/friendswine/tongtou/FriendsWinePlugin.class"));
            assertNull(zip.getEntry("com/friendswine/tongtou/TongtouPlugin.class"));
            assertEquals("FriendsWinePlugin-1.1.0-Paper-Folia.jar",jar.getFileName().toString());
            assertNull(zip.getEntry("com/friendswine/tongtou/BlockAnimations.class"));
            assertTrue(zip.stream().noneMatch(e -> e.getName().startsWith("net/minecraft/") || e.getName().startsWith("net/fabricmc/")));
        }
    }
}
