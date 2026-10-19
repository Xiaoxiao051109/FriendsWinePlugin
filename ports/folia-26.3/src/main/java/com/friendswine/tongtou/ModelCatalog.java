package com.friendswine.tongtou;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 匹配必须精确；未匹配时绝不使用同类型的错误变种冒充。 */
public final class ModelCatalog {
    public record Entry(String entity, String model, Map<String, String> when) {
        public Entry {
            Objects.requireNonNull(entity); Objects.requireNonNull(model);
            when = Map.copyOf(when);
            if (!entity.startsWith("minecraft:") || entity.equals("minecraft:player")
                    || !model.matches("tt_[a-z0-9_]+")) throw new IllegalArgumentException("非法模型目录项");
        }
        boolean matches(Map<String, String> state) {
            if (!state.getOrDefault("special_name", "").isEmpty() && !when.containsKey("special_name")) return false;
            if (state.getOrDefault("equipment", "empty").equals("present") && !when.containsKey("equipment")) return false;
            return when.entrySet().stream().allMatch(e -> e.getValue().equals(state.get(e.getKey())));
        }
    }
    private final Map<String, List<Entry>> entries;
    public ModelCatalog(Collection<Entry> values) {
        Map<String, List<Entry>> grouped = new HashMap<>();
        for (Entry entry : values) grouped.computeIfAbsent(entry.entity(), k -> new ArrayList<>()).add(entry);
        grouped.values().forEach(list -> list.sort(Comparator.comparingInt((Entry e) -> e.when().size()).reversed()));
        Map<String, List<Entry>> immutable = new HashMap<>();
        grouped.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        entries = Map.copyOf(immutable);
    }
    public Optional<Entry> resolve(String entity, Map<String, String> state) {
        if (entity.equals("minecraft:player")) return Optional.empty();
        return entries.getOrDefault(entity, List.of()).stream().filter(e -> e.matches(state)).findFirst();
    }
    public ModelCatalog forEntities(Set<String> types){
        return new ModelCatalog(entries.entrySet().stream().filter(e->types.contains(e.getKey())).flatMap(e->e.getValue().stream()).toList());
    }
    public boolean hasEntity(String type) { return entries.containsKey(type); }
    public int size() { return entries.values().stream().mapToInt(List::size).sum(); }
    public Set<String> modelNames() {
        Set<String> names = new TreeSet<>();
        entries.values().forEach(list -> list.forEach(entry -> names.add(entry.model())));
        return Set.copyOf(names);
    }
    public static ModelCatalog load(Path file) throws IOException {
        if (!Files.exists(file)) return new ModelCatalog(List.of());
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1 || !root.get("minecraft").getAsString().equals("26.3"))
                throw new IOException("模型目录不是schema=1的26.3资源");
            List<Entry> list = new ArrayList<>();
            for (JsonElement item : root.getAsJsonArray("models")) {
                JsonObject value = item.getAsJsonObject();
                Map<String, String> when = new HashMap<>();
                value.getAsJsonObject("when").entrySet().forEach(e -> when.put(e.getKey(), e.getValue().getAsString()));
                list.add(new Entry(value.get("entity").getAsString(), value.get("model").getAsString(), when));
            }
            return new ModelCatalog(list);
        } catch (RuntimeException failure) {
            throw new IOException("模型目录内容无效：" + file.getFileName(), failure);
        }
    }
}
