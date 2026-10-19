package com.friendswine.tongtou;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePackPathsTest {
    @TempDir Path temporary;
    @Test void relativeAndAbsoluteSameZipRegisteredOnce() throws Exception {
        Path zip = Files.writeString(temporary.resolve("build.zip"), "pack");
        Path relative = Path.of("").toAbsolutePath().relativize(zip.toAbsolutePath());
        Set<Path> zips = new LinkedHashSet<>(List.of(relative));
        ResourcePackPaths.register(zips, new LinkedHashSet<>(), zip.toAbsolutePath());
        assertEquals(1, zips.size());
        assertEquals(relative, zips.iterator().next(), "保留CE原有登记即可，不覆盖其配置");
    }
    @Test void deduplicatesExistingAliasesAndKeepsOtherPacks() throws Exception {
        Path zip = Files.writeString(temporary.resolve("build.zip"), "pack");
        Path other = Files.writeString(temporary.resolve("other.zip"), "other");
        Path alias = zip.getParent().resolve(".").resolve(zip.getFileName());
        Set<Path> zips = new LinkedHashSet<>(List.of(zip, alias, other));
        ResourcePackPaths.register(zips, new LinkedHashSet<>(), zip);
        assertEquals(2, zips.size()); assertTrue(zips.contains(other));
    }
    @Test void folderOutputAlsoDeduplicates() throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("output"));
        Path relative = Path.of("").toAbsolutePath().relativize(folder.toAbsolutePath());
        Set<Path> folders = new LinkedHashSet<>(List.of(relative));
        ResourcePackPaths.register(new LinkedHashSet<>(), folders, folder.toAbsolutePath());
        assertEquals(1, folders.size());
    }
    @Test void sameFileNameInDifferentFoldersIsNotRemoved() throws Exception {
        Path a = Files.writeString(Files.createDirectory(temporary.resolve("a")).resolve("build.zip"), "a");
        Path b = Files.writeString(Files.createDirectory(temporary.resolve("b")).resolve("build.zip"), "b");
        Set<Path> zips = new LinkedHashSet<>(List.of(a));
        ResourcePackPaths.register(zips, new LinkedHashSet<>(), b);
        assertEquals(2, zips.size());
    }
    @Test void eachReloadStillRegistersItsRequiredPack() throws Exception {
        Path zip = Files.writeString(temporary.resolve("build.zip"), "pack");
        for (int reload = 0; reload < 3; reload++) {
            Set<Path> zips = new LinkedHashSet<>();
            ResourcePackPaths.register(zips, new LinkedHashSet<>(), zip);
            ResourcePackPaths.register(zips, new LinkedHashSet<>(), zip);
            assertEquals(1, zips.size());
        }
    }
    @Test void invalidPackRejected() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePackPaths.register(new HashSet<>(), new HashSet<>(), temporary.resolve("missing.zip")));
    }
}
