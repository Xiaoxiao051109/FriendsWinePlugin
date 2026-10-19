package com.friendswine.tongtou;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 相同物理包即使同时以相对/绝对路径登记，也只交给CE一次。 */
final class ResourcePackPaths {
    private ResourcePackPaths() {}
    static void register(Set<Path> zips, Set<Path> folders, Path candidate) {
        deduplicate(zips);
        deduplicate(folders);
        Set<Path> target;
        if (Files.isDirectory(candidate)) target = folders;
        else if (Files.isRegularFile(candidate) && candidate.getFileName().toString().endsWith(".zip")) target = zips;
        else throw new IllegalArgumentException("不是有效的生成资源包：" + candidate);
        Path identity = identity(candidate);
        if (target.stream().map(ResourcePackPaths::identity).noneMatch(identity::equals)) target.add(candidate);
    }
    private static void deduplicate(Set<Path> paths) {
        Set<Path> identities = new HashSet<>();
        paths.removeIf(path -> !identities.add(identity(path)));
    }
    private static Path identity(Path path) {
        try { return path.toRealPath(); }
        catch (IOException unavailable) { return path.toAbsolutePath().normalize(); }
    }
}
