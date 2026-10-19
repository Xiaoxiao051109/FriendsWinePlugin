package com.friendswine.tongtou;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LegacyDataMigrationTest {
    @TempDir Path temporary;
    private Path old(){return temporary.resolve("TongtouMode");}
    private Path current(){return temporary.resolve("FriendsWinePlugin");}
    @Test void freshInstallDoesNotCreateLegacyData(){
        assertDoesNotThrow(()->assertEquals(0,LegacyDataMigration.migrate(old(),current())));
        assertFalse(Files.exists(old()));assertFalse(Files.exists(current()));
    }
    @Test void copiesKnownConfigClockAndLedgerWithoutMovingOriginals()throws Exception{
        Files.createDirectory(old());
        Files.writeString(old().resolve("config.yml"),"party:\n  orbit-speed: 0.5\n");
        Files.writeString(old().resolve("party-clock.txt"),"123456");
        Files.writeString(old().resolve(".installed-builtin.properties"),"models/tt_doll.bbmodel=old-managed-hash");
        Files.writeString(old().resolve("not-ours.txt"),"keep here");
        assertEquals(3,LegacyDataMigration.migrate(old(),current()));
        for(String name:List.of("config.yml","party-clock.txt",".installed-builtin.properties"))
            assertArrayEquals(Files.readAllBytes(old().resolve(name)),Files.readAllBytes(current().resolve(name)));
        assertFalse(Files.exists(current().resolve("not-ours.txt")));assertTrue(Files.exists(old().resolve("not-ours.txt")));
    }
    @Test void existingNewConfigAlwaysWins()throws Exception{
        Files.createDirectory(old());Files.createDirectory(current());
        Files.writeString(old().resolve("config.yml"),"old");Files.writeString(current().resolve("config.yml"),"admin-new");
        Files.writeString(old().resolve("party-clock.txt"),"100");
        assertEquals(1,LegacyDataMigration.migrate(old(),current()));
        assertEquals("admin-new",Files.readString(current().resolve("config.yml")));
    }
    @Test void migrationRunsOnceAndDoesNotResurrectDeletedOldConfig()throws Exception{
        Files.createDirectory(old());Files.writeString(old().resolve("config.yml"),"old");
        assertEquals(1,LegacyDataMigration.migrate(old(),current()));Files.delete(current().resolve("config.yml"));
        assertEquals(0,LegacyDataMigration.migrate(old(),current()));assertFalse(Files.exists(current().resolve("config.yml")));
    }
    @Test void directoriesCannotMasqueradeAsKnownFiles()throws Exception{
        Files.createDirectory(old());Files.createDirectory(old().resolve("config.yml"));
        assertThrows(IOException.class,()->LegacyDataMigration.migrate(old(),current()));
    }
    @Test void symbolicLinksCannotEscapeTheDataDirectory()throws Exception{
        Files.createDirectory(old());Path other=temporary.resolve("other.yml");Files.writeString(other,"outside");
        try{Files.createSymbolicLink(old().resolve("config.yml"),other);}
        catch(IOException|UnsupportedOperationException|SecurityException unavailable){assumeTrue(false,"当前环境无符号链接权限");}
        assertThrows(IOException.class,()->LegacyDataMigration.migrate(old(),current()));
        assertFalse(Files.exists(current().resolve("config.yml")));
    }
    @Test void pluginRenameKeepsAllExistingPdcKeysStable(){
        for(String name:List.of("mode","item_kind","doll_id","doll_mode","doll_elapsed","doll_rotated","doll_saved_clock",
                "remote_target","remote_world","remote_position","nitwit_day")){
            var key=DataKeys.key(name);assertEquals("tongtoumode",key.getNamespace());assertEquals(name,key.getKey());
        }
    }
}
