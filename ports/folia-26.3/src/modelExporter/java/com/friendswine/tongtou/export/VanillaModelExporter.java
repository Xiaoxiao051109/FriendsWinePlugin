package com.friendswine.tongtou.export;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

/** 仅在开发机加载官方客户端的模型构建代码；不启动游戏、窗口或服务器。 */
public final class VanillaModelExporter {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("参数：官方客户端JAR 输出目录");
        Path output = Path.of(args[1]);
        try (ZipFile jar = new ZipFile(args[0]); var reader = new java.io.InputStreamReader(
                jar.getInputStream(jar.getEntry("version.json")), java.nio.charset.StandardCharsets.UTF_8)) {
            if (!JsonParser.parseReader(reader).getAsJsonObject().get("id").getAsString().equals("26.3"))
                throw new IllegalArgumentException("只接受26.3官方客户端");
        }
        Files.createDirectories(output);
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        var roots = (Map<?, ?>) Class.forName("net.minecraft.client.model.geom.LayerDefinitions")
                .getMethod("createRoots").invoke(null);
        JsonObject result = new JsonObject();
        for (var entry : roots.entrySet()) {
            Object key = entry.getKey();
            String id = key.getClass().getMethod("model").invoke(key) + "#" + key.getClass().getMethod("layer").invoke(key);
            if (id.startsWith("minecraft:player#") || id.startsWith("minecraft:player_slim#")) continue;
            Object root = entry.getValue().getClass().getMethod("bakeRoot").invoke(entry.getValue());
            result.add(id, part(root));
        }
        Files.writeString(output.resolve("layers.json"), JSON.toJson(result));
        JsonObject palette = new JsonObject();
        Class<?> dyes = Class.forName("net.minecraft.world.item.DyeColor");
        for (Object dye : (Object[]) dyes.getMethod("values").invoke(null)) {
            int color = (int) dyes.getMethod("getTextureDiffuseColor").invoke(dye);
            JsonArray rgb = new JsonArray();
            rgb.add((color >> 16) & 255); rgb.add((color >> 8) & 255); rgb.add(color & 255);
            palette.add(dyes.getMethod("getName").invoke(dye).toString(), rgb);
        }
        Files.writeString(output.resolve("palette.json"), JSON.toJson(palette));
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        JsonObject source = new JsonObject();
        source.addProperty("minecraft", "26.3");
        source.addProperty("client_sha1", HexFormat.of().formatHex(digest.digest()));
        source.addProperty("layers", result.size());
        source.addProperty("cube_export", 1);
        Files.writeString(output.resolve("face-info.json"), JSON.toJson(faceInfo()));
        Object registry = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("ENTITY_TYPE").get(null);
        var keys = (Set<?>) Class.forName("net.minecraft.core.Registry").getMethod("keySet").invoke(registry);
        JsonArray entities = new JsonArray();
        keys.stream().map(Object::toString).filter(id -> !id.equals("minecraft:player")).sorted().forEach(entities::add);
        source.add("entities", entities);
        Files.writeString(output.resolve("source.json"), JSON.toJson(source));
        System.out.println("已导出非玩家模型层：" + result.size());
    }
    private static JsonObject part(Object part) throws Exception {
        JsonObject value = new JsonObject();
        for (String field : List.of("x", "y", "z", "xRot", "yRot", "zRot", "xScale", "yScale", "zScale")) {
            value.addProperty(field, part.getClass().getField(field).getFloat(part));
        }
        value.addProperty("visible", part.getClass().getField("visible").getBoolean(part));
        value.addProperty("skipDraw", part.getClass().getField("skipDraw").getBoolean(part));
        JsonArray polygons = new JsonArray();
        JsonArray cubes = new JsonArray();
        for (Object cube : (List<?>) field(part, "cubes")) {
            JsonArray faces = new JsonArray();
            for (Object polygon : (Object[]) cube.getClass().getField("polygons").get(cube)) {
                JsonArray vertices = new JsonArray();
                for (Object vertex : (Object[]) polygon.getClass().getMethod("vertices").invoke(polygon)) {
                    JsonArray point = new JsonArray();
                    for (String method : List.of("x", "y", "z", "u", "v")) {
                        point.add((Number) vertex.getClass().getMethod(method).invoke(vertex));
                    }
                    vertices.add(point);
                }
                JsonObject face = new JsonObject();
                face.add("vertices", vertices);
                Object normal = polygon.getClass().getMethod("normal").invoke(polygon);
                JsonArray direction = new JsonArray();
                for (String component : List.of("x", "y", "z"))
                    direction.add((Number) normal.getClass().getMethod(component).invoke(normal));
                face.add("normal", direction);
                faces.add(face);
                polygons.add(vertices);
            }
            JsonObject cell = new JsonObject(); cell.add("faces", faces); cubes.add(cell);
        }
        value.add("polygons", polygons);
        value.add("cubes", cubes);
        JsonObject children = new JsonObject();
        for (var child : ((Map<?, ?>) field(part, "children")).entrySet()) {
            children.add(child.getKey().toString(), part(child.getValue()));
        }
        value.add("children", children);
        return value;
    }
    private static JsonObject faceInfo() throws Exception {
        JsonObject result = new JsonObject();
        Class<?> faceType = Class.forName("net.minecraft.client.renderer.FaceInfo");
        for (Object face : (Object[]) faceType.getMethod("values").invoke(null)) {
            JsonArray vertices = new JsonArray();
            for (int index = 0; index < 4; index++) {
                Object info = faceType.getMethod("getVertexInfo", int.class).invoke(face, index);
                JsonArray corner = new JsonArray();
                for (String component : List.of("xFace", "yFace", "zFace")) {
                    Object extent = info.getClass().getMethod(component).invoke(info);
                    corner.add(extent.toString().startsWith("MAX") ? 1 : 0);
                }
                vertices.add(corner);
            }
            result.add(((Enum<?>) face).name().toLowerCase(java.util.Locale.ROOT), vertices);
        }
        Class<?> uvType = Class.forName("net.minecraft.client.resources.model.cuboid.CuboidFace$UVs");
        Class<?> faceUv = Class.forName("net.minecraft.client.resources.model.cuboid.CuboidFace");
        Class<?> quadrantType = Class.forName("com.mojang.math.Quadrant");
        Object uv = uvType.getConstructor(float.class, float.class, float.class, float.class).newInstance(0F, 0F, 16F, 16F);
        JsonObject rotations = new JsonObject();
        for (Object quadrant : (Object[]) quadrantType.getMethod("values").invoke(null)) {
            JsonArray corners = new JsonArray();
            for (int index = 0; index < 4; index++) {
                JsonArray point = new JsonArray();
                for (String method : List.of("getU", "getV"))
                    point.add((Number) faceUv.getMethod(method, uvType, quadrantType, int.class).invoke(null, uv, quadrant, index));
                corners.add(point);
            }
            rotations.add(((Enum<?>) quadrant).name().substring(1), corners);
        }
        result.add("uv_rotations", rotations);
        return result;
    }
    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}
