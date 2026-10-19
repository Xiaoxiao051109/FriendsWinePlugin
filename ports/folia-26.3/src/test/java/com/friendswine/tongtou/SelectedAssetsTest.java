package com.friendswine.tongtou;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SelectedAssetsTest {
    @TempDir Path temporary;
    private static final Set<String> TYPES=Set.of("minecraft:pig","minecraft:slime","minecraft:drowned");
    private BuiltinAssets.Source source(String version,String bm,int java)throws Exception{return SelectedAssets.select(BuiltinAssets.bundled(),ServerProfile.select(version,bm,java),TYPES);}
    private JsonObject json(BuiltinAssets.Source source,String path)throws Exception{return JsonParser.parseString(new String(BuiltinAssets.read(source,path,16*1024*1024),StandardCharsets.UTF_8)).getAsJsonObject();}
    @Test void selectedCatalogContainsOnlyExistingServerTypesAndKeepsUnifiedSource()throws Exception{
        var source=source("1.21.1","1.15.2",21);var catalog=json(source,"catalog.json");
        assertEquals("26.3",catalog.get("minecraft").getAsString());assertEquals("1.21.1",catalog.get("target_minecraft").getAsString());
        for(var value:catalog.getAsJsonArray("models"))assertTrue(TYPES.contains(value.getAsJsonObject().get("entity").getAsString()));
        assertEquals(11,catalog.getAsJsonArray("models").size());assertEquals(12,json(source,"index.json").get("models").getAsInt());
        var result=BuiltinAssets.install(source,temporary.resolve("own"),temporary.resolve("bm"),temporary.resolve("ce"));
        assertEquals(12,result.models());assertFalse(Files.exists(temporary.resolve("bm/models/tt_parched.bbmodel")));
    }
    @Test void classicCeConfigurationDoesNotInvokeBm3AndUsesCompatibleItemComponents()throws Exception{
        var source=source("1.21.1","1.15.2",21);var yaml=new YamlConfiguration();
        yaml.loadFromString(new String(BuiltinAssets.read(source,"ce/configuration/items.yml",65536),StandardCharsets.UTF_8));
        assertNull(yaml.get("items.tongtou:doll.behavior.furniture.variants.ground.blueprint"));
        assertEquals("tongtou:item/doll_legacy",yaml.getString("items.tongtou:doll.model"));
        assertNull(yaml.get("items.tongtou:remote.data.components.minecraft:consumable"));
        assertEquals(3600.0,yaml.getDouble("items.tongtou:remote.data.components.minecraft:food.eat_seconds"));
        assertEquals(51,json(source,"ce/resourcepack/assets/tongtou/models/item/doll_legacy.json").getAsJsonArray("elements").size());
    }
    @Test void componentAndModelBoundariesAreIndependent()throws Exception{
        var source=source("1.21.3","1.15.2",21);var yaml=new YamlConfiguration();
        yaml.loadFromString(new String(BuiltinAssets.read(source,"ce/configuration/items.yml",65536),StandardCharsets.UTF_8));
        assertNotNull(yaml.get("items.tongtou:remote.data.components.minecraft:consumable"));
        assertEquals("tongtou:item/doll_legacy",yaml.getString("items.tongtou:doll.model"));
        var modern=source("1.21.4","3.5.0",25);var doll=json(modern,"ce/resourcepack/assets/tongtou/items/doll.json");
        assertEquals(51,doll.getAsJsonObject("model").getAsJsonArray("models").size());
        for(var value:doll.getAsJsonObject("model").getAsJsonArray("models"))assertFalse(value.getAsJsonObject().has("transformation"));
        assertNotNull(json(modern,"ce/resourcepack/assets/tongtou/models/doll/cube_0.json").getAsJsonObject("display"));
    }
    @Test void switchingFromFullInstallBacksUpAbsentManagedModelsWithoutDeletingData()throws Exception{
        Path own=temporary.resolve("own"),bm=temporary.resolve("bm"),ce=temporary.resolve("ce");
        BuiltinAssets.install(BuiltinAssets.bundled(),own,bm,ce);byte[] before=Files.readAllBytes(bm.resolve("models/tt_parched.bbmodel"));
        BuiltinAssets.install(source("1.21.1","1.15.2",21),own,bm,ce);
        assertFalse(Files.exists(bm.resolve("models/tt_parched.bbmodel")));
        try(var paths=Files.walk(own.resolve("compat-backups"))){
            var backup=paths.filter(p->p.getFileName().toString().equals("tt_parched.bbmodel")).findFirst().orElseThrow();assertArrayEquals(before,Files.readAllBytes(backup));
        }
        BuiltinAssets.install(BuiltinAssets.bundled(),own,bm,ce);assertArrayEquals(before,Files.readAllBytes(bm.resolve("models/tt_parched.bbmodel")));
    }
    @Test void adminModifiedAbsentModelBlocksSwitchAndIsNeverMoved()throws Exception{
        Path own=temporary.resolve("own"),bm=temporary.resolve("bm"),ce=temporary.resolve("ce");
        BuiltinAssets.install(BuiltinAssets.bundled(),own,bm,ce);Path model=bm.resolve("models/tt_parched.bbmodel");Files.writeString(model,"admin");
        assertThrows(java.io.IOException.class,()->BuiltinAssets.install(source("1.21.1","1.15.2",21),own,bm,ce));
        assertEquals("admin",Files.readString(model));
    }
    @Test void profileSelectionIncludesEvery121ReleaseAndChecksJavaAndBackend(){
        for(int patch=0;patch<=11;patch++)assertTrue(ServerProfile.select(patch==0?"1.21":"1.21."+patch,"1.15.2",21).legacyModels());
        for(String mc:List.of("26.1","26.1.2","26.2","26.3"))assertFalse(ServerProfile.select(mc,"3.5.0",25).legacyModels());
        assertThrows(IllegalArgumentException.class,()->ServerProfile.select("1.21.3","3.5.0",25));
        assertThrows(IllegalArgumentException.class,()->ServerProfile.select("26.3","3.5.0",21));
        assertThrows(IllegalArgumentException.class,()->ServerProfile.select("26.3","1.15.2",25));
        assertThrows(IllegalArgumentException.class,()->ServerProfile.select("1.20.6","1.15.2",21));
    }
    @Test void legacyBoatIdMapsOnlyExistingWoodVariants()throws Exception{
        var source=SelectedAssets.select(BuiltinAssets.bundled(),ServerProfile.select("1.21.1","1.15.2",21),Set.of("minecraft:boat"));
        var catalog=json(source,"catalog.json");
        assertEquals(9,catalog.getAsJsonArray("models").size());
        for(var raw:catalog.getAsJsonArray("models")){
            var entry=raw.getAsJsonObject();assertEquals("minecraft:boat",entry.get("entity").getAsString());
            assertNotEquals("pale_oak",entry.getAsJsonObject("when").get("boat_type").getAsString());
        }
    }
}
