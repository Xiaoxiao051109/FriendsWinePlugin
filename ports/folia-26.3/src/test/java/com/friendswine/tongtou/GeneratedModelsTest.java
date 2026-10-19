package com.friendswine.tongtou;

import java.nio.file.*;
import java.util.*;
import kr.toxicity.model.api.data.raw.ModelData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import java.lang.reflect.Proxy;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.BetterModelPlatform;
import kr.toxicity.model.api.BetterModelConfig;
import kr.toxicity.model.api.data.blueprint.BlueprintElement;
import kr.toxicity.model.api.pack.PackObfuscator;
import com.google.gson.*;
import java.util.zip.ZipInputStream;
import java.util.Base64;
import kr.toxicity.model.api.platform.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedModelsTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    @BeforeAll static void offlinePlatform() {
        PlatformItemStack air = new PlatformItemStack() {
            public boolean isAir() { return true; }
            public PlatformItemStack enchant(boolean value) { return this; }
            public PlatformItemStack itemModel(PlatformNamespace value) { return this; }
            public PlatformItemStack clone() { return this; }
        };
        var adapter = (PlatformAdapter) Proxy.newProxyInstance(PlatformAdapter.class.getClassLoader(),
                new Class<?>[]{PlatformAdapter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("air")) return air;
                    throw new AssertionError("离线解析不应访问世界：" + method.getName());
                });
        var config = (BetterModelConfig) Proxy.newProxyInstance(BetterModelConfig.class.getClassLoader(),
                new Class<?>[]{BetterModelConfig.class}, (proxy, method, args) -> {
                    if (method.getName().equals("namespace")) return "bettermodel";
                    throw new AssertionError("网格编译不应访问其他配置：" + method.getName());
                });
        var platform = (BetterModelPlatform) Proxy.newProxyInstance(BetterModelPlatform.class.getClassLoader(),
                new Class<?>[]{BetterModelPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("adapter")) return adapter;
                    if (method.getName().equals("config")) return config;
                    throw new AssertionError("离线解析不应启动平台：" + method.getName());
                });
        BetterModel.register(platform);
    }
    @Test void everyGeneratedModelCompilesWithPngTextureAndUvInsteadOfConstantColorTriangles() throws Exception {
        var installed = BuiltinAssets.install(BuiltinAssets.bundled(), temporary.resolve("own"), temporary.resolve("models"), null);
        assertEquals(823, installed.models());
        Path directory = temporary.resolve("models/models");
        List<Path> files;
        try (var paths = Files.list(directory)) { files = paths.filter(p -> p.toString().endsWith(".bbmodel")).toList(); }
        assertEquals(823, files.size());
        for (Path file : files) {
            try (var reader = Files.newBufferedReader(file)) {
                ModelData data = ModelData.GSON.fromJson(reader, ModelData.class);
                data.assertSupported();
                var loaded = data.loadBlueprint(file.getFileName().toString(), true);
                assertTrue(loaded.errors().isEmpty(), file.getFileName() + ": " + loaded.errors());
                assertFalse(loaded.blueprint().elements().isEmpty(), file.toString());
                var context = loaded.blueprint().context();
                int sourceCubes = data.elements().size();
                int compiledCubes = 0;
                for (var element : loaded.blueprint().elements()) {
                    if (element instanceof BlueprintElement.Group group) {
                        assertNull(group.buildMeshItemModel(context), "不应再走三角形平均颜色路径：" + file);
                        var models = group.buildModernJson(PackObfuscator.pair(PackObfuscator.NONE, PackObfuscator.NONE), context);
                        assertNotNull(models, file.toString());
                        for (var model : models) {
                            var json = model.buildJson().getAsJsonObject();
                            assertTrue(json.has("textures"), file.toString());
                            for (var cubeValue : json.getAsJsonArray("elements")) {
                                compiledCubes++;
                                var cube = cubeValue.getAsJsonObject();
                                assertFalse(cube.getAsJsonObject("faces").isEmpty(), file.toString());
                                for (var face : cube.getAsJsonObject("faces").entrySet()) {
                                    var texture = face.getValue().getAsJsonObject().get("texture").getAsString();
                                    assertTrue(texture.startsWith("#"));
                                    assertTrue(json.getAsJsonObject("textures").has(texture.substring(1)), file.toString());
                                }
                            }
                        }
                    }
                }
                assertEquals(sourceCubes, compiledCubes, "不能悄悄丢掉立方体：" + file);
                var images = context.buildImage(PackObfuscator.NONE).toList();
                assertEquals(data.textures().size(), images.size(), "PNG必须实际进入最终资源包：" + file);
                for (int i = 0; i < images.size(); i++) {
                    assertArrayEquals(loaded.blueprint().textures().get(i).image(), images.get(i).image(), file.toString());
                }

            }
        }
    }
    @Test void original011AxolotlReproducesReportedCompilerFailure() throws Exception {
        try (var stream = getClass().getResourceAsStream("/regressions/axolotl-0.1.1.bbmodel")) {
            assertNotNull(stream);
            var data = ModelData.GSON.fromJson(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8), ModelData.class);
            var blueprint = data.loadBlueprint("original_011_axolotl", true).blueprint();
            var context = blueprint.context();
            var group = (BlueprintElement.Group) blueprint.elements().getFirst();
            var failure = assertThrows(IllegalArgumentException.class, () -> group.buildMeshItemModel(context));
            assertEquals("Degenerate polygon: cannot compute normal", failure.getMessage());
        }
    }

    @Test void legacyPigMeshContainsBlackConstantColorTriangles() throws Exception {
        try (var stream = new ZipInputStream(getClass().getResourceAsStream("/regressions/mesh-models-0.1.3.zip"))) {
            for (var entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
                if (!entry.getName().equals("models/tt_pig_temperate.bbmodel")) continue;
                var text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                var data = ModelData.GSON.fromJson(text, ModelData.class);
                var blueprint = data.loadBlueprint("legacy_pig", true).blueprint();
                var context = blueprint.context();
                var json = ((BlueprintElement.Group) blueprint.elements().getFirst()).buildMeshItemModel(context);
                int black = 0;
                var models = json.getAsJsonArray("models");
                for (var model : models) {
                    int tint = model.getAsJsonObject().getAsJsonArray("tints").get(0).getAsJsonObject().get("value").getAsInt();
                    if ((tint & 0xFFFFFF) == 0) black++;
                }
                assertTrue(black > models.size() / 3, "应复现大片黑色而不是只证明模型可解析");
                System.out.println("旧版猪模型单色块=" + models.size() + "，其中黑块=" + black);
                return;
            }
            fail("缺少旧版猪模型");
        }
    }

}
