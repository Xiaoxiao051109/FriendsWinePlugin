package com.friendswine.tongtou;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 安装经过校验的内置资源；升级仅替换未被管理员修改的受管理文件。 */
public final class BuiltinAssets {
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final String LEDGER = ".installed-builtin.properties";
    private BuiltinAssets() {}
    @FunctionalInterface public interface Source { InputStream open(String relative) throws IOException; }
    public static Source bundled() {
        return relative -> BuiltinAssets.class.getResourceAsStream("/bundled/" + relative);
    }
    public record InstallResult(int created, int updated, int models, List<String> preserved) {
        public boolean changed() { return created + updated > 0; }
    }
    public static InstallResult install(Source source, Path own, Path betterModel, Path craftEngine) throws IOException {
        Properties before = new Properties(), after = new Properties();
        Path ledger = safe(own, LEDGER);
        if (Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) {
            try (var input = Files.newInputStream(ledger)) { before.load(input); }
        }
        byte[] indexBytes = read(source, "index.json", 1024 * 1024);
        JsonObject index;
        try {
            index = JsonParser.parseString(new String(indexBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (index.get("schema").getAsInt() != 1 || !"26.3".equals(index.get("minecraft").getAsString()))
                throw new IOException("内置资源不是26.3 schema=1");
        } catch (RuntimeException error) { throw new IOException("内置资源清单无效", error); }
        JsonArray files = index.getAsJsonArray("files");
        if (files == null || files.size() > 10000) throw new IOException("内置文件数量无效");
        int created = 0, updated = 0, models = 0;
        long total = 0;
        Set<String> paths = new HashSet<>();
        List<String> preserved = new ArrayList<>();
        record Prepared(String relative,Path target,byte[] bytes,String hash) {}
        List<Prepared> prepared=new ArrayList<>();
        for (var element : files) {
            JsonObject entry = element.getAsJsonObject();
            String relative = entry.get("path").getAsString();
            if (!paths.add(relative)) throw new IOException("重复内置资源：" + relative);
            Path target;
            if(relative.matches("compat/(classic-models|classic-items|modern-items)[.]zip")) target=null;
            else if (relative.matches("models/tt_[a-z0-9_]+[.]bbmodel")) {
                target = safe(betterModel.resolve("models"), relative.substring(7)); models++;
            } else if (relative.equals("catalog.json") || relative.equals("coverage.json")
                    || relative.equals("music.json") || relative.equals("feature-models.json")) target = safe(own, relative);
            else if (relative.equals("ce/pack.yml") || relative.equals("ce/configuration/items.yml")
                    || relative.equals("ce/configuration/categories.yml")
                    || relative.matches("ce/resourcepack/assets/tongtou/items/(doll|remote)[.]json")
                    || relative.matches("ce/resourcepack/assets/tongtou/models/(doll/cube_[0-9]+|item/(remote|doll_legacy))[.]json")
                    || relative.matches("ce/resourcepack/assets/tongtou/textures/(entity/doll|item/remote)[.]png")
                    || relative.matches("ce/resourcepack/assets/tongtou/sounds/party/music_[0-9]+[.]ogg")
                    || relative.equals("ce/resourcepack/assets/tongtou/sounds.json")) {
                target = craftEngine == null ? null : safe(craftEngine.resolve("resources/tongtou"), relative.substring(3));
            } else throw new IOException("不允许的内置资源路径：" + relative);
            int size = entry.get("size").getAsInt();
            if (size < 0 || size > MAX_FILE_BYTES || (total += size) > 128L * 1024 * 1024)
                throw new IOException("内置资源超过大小限制");
            byte[] bytes = read(source, relative, size);
            String expected = entry.get("sha256").getAsString();
            if (bytes.length != size || !hash(bytes).equals(expected)) throw new IOException("内置资源校验失败：" + relative);
            if(target!=null)prepared.add(new Prepared(relative,target,bytes,expected));
        }
        if (models < 1 || models != index.get("models").getAsInt()) throw new IOException("内置模型数量与清单不符");
        archiveObsolete(before,paths,own,betterModel);
        for(var asset:prepared){
            String relative=asset.relative(),expected=asset.hash();Path target=asset.target();byte[] bytes=asset.bytes();
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("资源目标不是普通文件：" + target);
                String actual = hashFile(target);
                if (actual.equals(expected)) { after.setProperty(relative, expected); continue; }
                if (!actual.equals(before.getProperty(relative))) {
                    preserved.add(relative);
                    if (before.containsKey(relative)) after.setProperty(relative, before.getProperty(relative));
                    continue;
                }
            }
            write(target, bytes, exists);
            after.setProperty(relative, expected);
            if (exists) updated++; else created++;
        }
        var output = new ByteArrayOutputStream();
        after.store(output, "FriendsWinePlugin managed builtin assets");
        write(ledger, output.toByteArray(), Files.exists(ledger, LinkOption.NOFOLLOW_LINKS));
        return new InstallResult(created, updated, models, List.copyOf(preserved));
    }
    private static void archiveObsolete(Properties before,Set<String> selected,Path own,Path betterModel)throws IOException {
        List<Path> obsolete=new ArrayList<>();
        for(String name:before.stringPropertyNames())if(name.matches("models/tt_[a-z0-9_]+[.]bbmodel")&&!selected.contains(name)){
            Path path=safe(betterModel.resolve("models"),name.substring(7));
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!hashFile(path).equals(before.getProperty(name)))
                throw new IOException("目标版本不加载该模型，但文件已被管理员修改，请先手工处理："+path);
            obsolete.add(path);
        }
        if(obsolete.isEmpty())return;String backup="compat-backups/"+UUID.randomUUID()+"/models/";
        for(Path path:obsolete){Path target=safe(own,backup+path.getFileName());Files.createDirectories(target.getParent());Files.move(path,target);}
    }
    static byte[] read(Source source, String path, int maximum) throws IOException {
        try (InputStream stream = source.open(path)) {
            if (stream == null) throw new FileNotFoundException("JAR内缺少 bundled/" + path);
            byte[] bytes = stream.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("内置资源超出声明长度：" + path);
            return bytes;
        }
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hashFile(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static Path safe(Path root, String relative) throws IOException {
        if (relative.isBlank() || relative.contains("\\") || relative.startsWith("/") || relative.contains(":"))
            throw new IOException("非法内置资源路径");
        for (String segment : relative.split("/", -1))
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) throw new IOException("非法内置资源路径");
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) throw new IOException("内置资源越界");
        for (Path part = target; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("内置资源路径包含符号链接：" + part);
        return target;
    }
    private static void write(Path target, byte[] bytes, boolean replace) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".tongtou-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (replace) {
                try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            } else Files.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }
}
