package com.friendswine.tongtou;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinAssetsTest {
    @TempDir Path temporary;
    private Map<String, byte[]> source(String model) {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("models/tt_test.bbmodel", model.getBytes(StandardCharsets.UTF_8));
        files.put("catalog.json", "{\"schema\":1,\"minecraft\":\"26.3\",\"models\":[]}".getBytes(StandardCharsets.UTF_8));
        files.put("coverage.json", "{}".getBytes(StandardCharsets.UTF_8));
        files.put("ce/pack.yml", "namespace: tongtou".getBytes(StandardCharsets.UTF_8));
        var index = new JsonObject(); index.addProperty("schema", 1); index.addProperty("minecraft", "26.3"); index.addProperty("models", 1);
        var entries = new JsonArray();
        files.forEach((path, bytes) -> {
            var entry = new JsonObject(); entry.addProperty("path", path); entry.addProperty("size", bytes.length);
            entry.addProperty("sha256", BuiltinAssets.hash(bytes)); entries.add(entry);
        });
        index.add("files", entries);
        files.put("index.json", index.toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }
    private BuiltinAssets.Source input(Map<String, byte[]> files) {
        return path -> files.containsKey(path) ? new ByteArrayInputStream(files.get(path)) : null;
    }
    private BuiltinAssets.InstallResult install(Map<String, byte[]> files, boolean ce) throws IOException {
        return BuiltinAssets.install(input(files), temporary.resolve("own"), temporary.resolve("BetterModel"), ce ? temporary.resolve("CraftEngine") : null);
    }
    @Test void firstStartAndRepeatedStart() throws Exception {
        var files = source("first");
        assertEquals(4, install(files, true).created());
        var again = install(files, true);
        assertFalse(again.changed()); assertTrue(again.preserved().isEmpty());
        assertEquals("first", Files.readString(temporary.resolve("BetterModel/models/tt_test.bbmodel")));
        assertTrue(Files.exists(temporary.resolve("CraftEngine/resources/tongtou/pack.yml")));
    }
    @Test void repairsDeletedModelEvenWithLedgerPresent() throws Exception {
        var files = source("first"); install(files, false);
        Files.delete(temporary.resolve("BetterModel/models/tt_test.bbmodel"));
        assertEquals(1, install(files, false).created());
    }
    @Test void untouchedManagedFileUpgrades() throws Exception {
        install(source("old"), false);
        assertEquals(1, install(source("new"), false).updated());
        assertEquals("new", Files.readString(temporary.resolve("BetterModel/models/tt_test.bbmodel")));
    }
    @Test void preservesAdminChangesAndOtherPluginModels() throws Exception {
        install(source("old"), false);
        Path target = temporary.resolve("BetterModel/models/tt_test.bbmodel");
        Files.writeString(target, "custom");
        Path foreign = temporary.resolve("BetterModel/models/other_plugin.bbmodel"); Files.writeString(foreign, "keep");
        var update = install(source("new"), false);
        assertEquals(List.of("models/tt_test.bbmodel"), update.preserved());
        assertEquals("custom", Files.readString(target)); assertEquals("keep", Files.readString(foreign));
    }
    @Test void lateCraftEngineInstallIsNotSkipped() throws Exception {
        install(source("first"), false);
        assertEquals(1, install(source("first"), true).created());
    }
    @Test void missingAndCorruptEmbeddedAssetsFailClosed() {
        var missing = source("first"); missing.remove("models/tt_test.bbmodel");
        assertThrows(IOException.class, () -> install(missing, false));
        var corrupt = source("first"); corrupt.put("models/tt_test.bbmodel", "bad".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> install(corrupt, false));
    }
    @Test void rejectsTraversalAndUnexpectedFiles() {
        assertThrows(IOException.class, () -> BuiltinAssets.safe(temporary, "../outside"));
        assertThrows(IOException.class, () -> BuiltinAssets.safe(temporary, "/outside"));
        assertThrows(IOException.class, () -> BuiltinAssets.safe(temporary, "C:/outside"));
        assertThrows(IOException.class, () -> BuiltinAssets.safe(temporary, "a//b"));
    }
    @Test void actualClasspathContainsEverythingAndInstallsWithoutExternalZip() throws Exception {
        var installed = BuiltinAssets.install(BuiltinAssets.bundled(), temporary.resolve("live/TongtouMode"),
                temporary.resolve("live/BetterModel"), temporary.resolve("live/CraftEngine"));
        assertEquals(823, installed.models());
        assertEquals(822, ModelCatalog.load(temporary.resolve("live/TongtouMode/catalog.json")).size());
        assertTrue(Files.size(temporary.resolve("live/BetterModel/models/tt_pig_temperate.bbmodel")) > 100);
        assertTrue(Files.exists(temporary.resolve("live/CraftEngine/resources/tongtou/configuration/categories.yml")));
    }
    @Test void upgradesInstalled011BrokenModelAndAddsCategoryWithoutManualDeletion() throws Exception {
        Path own = temporary.resolve("upgrade/TongtouMode");
        Path bm = temporary.resolve("upgrade/BetterModel");
        Path ce = temporary.resolve("upgrade/CraftEngine");
        Path oldModel = bm.resolve("models/tt_axolotl_blue.bbmodel");
        Files.createDirectories(oldModel.getParent());
        byte[] broken;
        try (var input = getClass().getResourceAsStream("/regressions/axolotl-0.1.1.bbmodel")) {
            assertNotNull(input); broken = input.readAllBytes();
        }
        Files.write(oldModel, broken);
        var ledger = new Properties();
        ledger.setProperty("models/tt_axolotl_blue.bbmodel", BuiltinAssets.hash(broken));
        Files.createDirectories(own);
        try (var output = Files.newOutputStream(own.resolve(".installed-builtin.properties"))) { ledger.store(output, "0.1.1"); }
        var installed = BuiltinAssets.install(BuiltinAssets.bundled(), own, bm, ce);
        assertEquals(1, installed.updated());
        assertTrue(installed.preserved().isEmpty());
        assertNotEquals(BuiltinAssets.hash(broken), BuiltinAssets.hash(Files.readAllBytes(oldModel)));
        assertTrue(Files.exists(ce.resolve("resources/tongtou/configuration/categories.yml")));
        assertFalse(BuiltinAssets.install(BuiltinAssets.bundled(), own, bm, ce).changed());
    }

    @Test void upgradesAllExisting013MeshesToRealTexturedCubes() throws Exception {
        Map<String, byte[]> legacy = new HashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(getClass().getResourceAsStream("/regressions/mesh-models-0.1.3.zip"))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (!entry.isDirectory()) legacy.put(entry.getName(), zip.readAllBytes());
        }
        Path own = temporary.resolve("v013/TongtouMode"), bm = temporary.resolve("v013/BetterModel"), ce = temporary.resolve("v013/CraftEngine");
        assertEquals(809, BuiltinAssets.install(input(legacy), own, bm, ce).models());
        var upgraded = BuiltinAssets.install(BuiltinAssets.bundled(), own, bm, ce);
        assertTrue(upgraded.updated() >= 809, "全部旧网格应安全更新为纹理立方体");
        assertTrue(upgraded.preserved().isEmpty(), "不能把插件自己的未修改资源误判为管理员修改");
        var pig = JsonParser.parseString(Files.readString(bm.resolve("models/tt_pig_temperate.bbmodel"))).getAsJsonObject();
        assertEquals("cube", pig.getAsJsonArray("elements").get(0).getAsJsonObject().get("type").getAsString());
        assertFalse(BuiltinAssets.install(BuiltinAssets.bundled(), own, bm, ce).changed());
    }

}
