package com.friendswine.tongtou;

import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModelCatalogTest {
    @TempDir Path temporary;
    @Test void mostSpecificVariantWins() {
        var catalog = new ModelCatalog(List.of(
                new ModelCatalog.Entry("minecraft:pig", "tt_pig", Map.of("baby", "false")),
                new ModelCatalog.Entry("minecraft:pig", "tt_cold_pig", Map.of("baby", "false", "variant", "minecraft:cold"))));
        assertEquals("tt_cold_pig", catalog.resolve("minecraft:pig", Map.of("baby", "false", "variant", "minecraft:cold")).orElseThrow().model());
        assertTrue(catalog.resolve("minecraft:pig", Map.of("baby", "true")).isEmpty());
    }
    @Test void neverAcceptPlayerOrUnknownEntity() {
        assertThrows(IllegalArgumentException.class, () -> new ModelCatalog.Entry("minecraft:player", "tt_player", Map.of()));
        var catalog = new ModelCatalog(List.of());
        assertTrue(catalog.resolve("minecraft:player", Map.of()).isEmpty());
        assertTrue(catalog.resolve("minecraft:unknown", Map.of()).isEmpty());
    }
    @Test void noImplicitEquipmentOrNamedDisguise() {
        var catalog = new ModelCatalog(List.of(new ModelCatalog.Entry("minecraft:zombie", "tt_zombie", Map.of())));
        assertTrue(catalog.resolve("minecraft:zombie", Map.of("equipment", "present")).isEmpty());
        assertTrue(catalog.resolve("minecraft:zombie", Map.of("special_name", "Dinnerbone")).isEmpty());
    }
    @Test void invalidPathsAndDocumentsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ModelCatalog.Entry("minecraft:pig", "../other", Map.of()));
        Path file = temporary.resolve("catalog.json");
        Files.writeString(file, "{\"schema\":1,\"minecraft\":\"26.1.2\",\"models\":[]}");
        assertThrows(java.io.IOException.class, () -> ModelCatalog.load(file));
    }
    @Test void validFileLoadsAndAbsentFileIsEmpty() throws Exception {
        Path file = temporary.resolve("catalog.json");
        assertEquals(0, ModelCatalog.load(file).size());
        Files.writeString(file, "{\"schema\":1,\"minecraft\":\"26.3\",\"models\":[{\"entity\":\"minecraft:pig\",\"model\":\"tt_pig\",\"when\":{}}]}");
        assertEquals(1, ModelCatalog.load(file).size());
    }
}
