package com.friendswine.tongtou;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** 同一26.3源按客户端格式表达，并且仅安装目标服务器存在的实体模型。 */
final class SelectedAssets {
    private static final int MAX=16*1024*1024;
    private SelectedAssets() {}
    static BuiltinAssets.Source select(BuiltinAssets.Source source,ServerProfile profile,Set<String> entities)throws IOException {
        JsonObject original=json(BuiltinAssets.read(source,"index.json",1024*1024));
        Map<String,JsonObject> manifest=new LinkedHashMap<>();
        for(var value:original.getAsJsonArray("files")){
            var entry=value.getAsJsonObject();String path=entry.get("path").getAsString();
            if(manifest.putIfAbsent(path,entry)!=null)throw new IOException("重复资源路径："+path);
        }
        Map<String,byte[]> overrides=new HashMap<>();
        if(!profile.itemDefinitions()){
            overrides.putAll(variant(source,manifest,"compat/classic-models.zip"));
            overrides.putAll(variant(source,manifest,"compat/classic-items.zip"));
        }else if(profile.line()==121)overrides.putAll(variant(source,manifest,"compat/modern-items.zip"));
        JsonObject catalog=json(verified(source,manifest,"catalog.json"));JsonArray selected=new JsonArray();
        Set<String> models=new HashSet<>();
        for(var value:catalog.getAsJsonArray("models")){
            var entry=value.getAsJsonObject();String type=entry.get("entity").getAsString();
            if(!entities.contains(type)){
                String wood=type.startsWith("minecraft:")?type.substring(10):type;
                wood=wood.equals("bamboo_raft")?"bamboo":wood.endsWith("_boat")?wood.substring(0,wood.length()-5):"";
                if(!entities.contains("minecraft:boat")||!Set.of("oak","spruce","birch","jungle","acacia","dark_oak","mangrove","cherry","bamboo").contains(wood))continue;
                entry=entry.deepCopy();entry.addProperty("entity","minecraft:boat");entry.getAsJsonObject("when").addProperty("boat_type",wood);
            }
            selected.add(entry);models.add("models/"+entry.get("model").getAsString()+".bbmodel");
        }
        catalog.add("models",selected);catalog.addProperty("target_minecraft",profile.minecraft());catalog.addProperty("resource_profile",profile.resourceKey());
        overrides.put("catalog.json",bytes(catalog));
        JsonObject features=json(verified(source,manifest,"feature-models.json"));
        for(var name:features.getAsJsonArray("models"))models.add("models/"+name.getAsString()+".bbmodel");
        overrides.put("ce/configuration/items.yml",items(verified(source,manifest,"ce/configuration/items.yml"),profile));
        JsonObject coverage=json(verified(source,manifest,"coverage.json"));coverage.addProperty("target_minecraft",profile.minecraft());
        coverage.addProperty("active_models",selected.size());coverage.addProperty("filtered_models",original.get("vanilla_models").getAsInt()-selected.size());
        overrides.put("coverage.json",bytes(coverage));
        Map<String,JsonObject> entries=new TreeMap<>();
        for(var entry:manifest.entrySet()){
            String path=entry.getKey();
            if(path.startsWith("compat/")||path.startsWith("models/")&&!models.contains(path))continue;
            entries.put(path,entry.getValue());
        }
        for(var override:overrides.entrySet()){
            String path=override.getKey();if(path.startsWith("models/")&&!models.contains(path))continue;
            entries.put(path,entry(path,override.getValue()));
        }
        JsonObject index=original.deepCopy();JsonArray files=new JsonArray();entries.values().forEach(files::add);
        index.add("files",files);index.addProperty("models",models.size());index.addProperty("vanilla_models",selected.size());
        index.addProperty("target_minecraft",profile.minecraft());index.addProperty("resource_profile",profile.resourceKey());
        overrides.put("index.json",bytes(index));
        return path->{byte[] value=overrides.get(path);return value!=null?new ByteArrayInputStream(value):source.open(path);};
    }
    private static JsonObject json(byte[] bytes)throws IOException {
        try{return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(RuntimeException failure){throw new IOException("资源元数据无效",failure);}
    }
    private static byte[] bytes(JsonObject value){return value.toString().getBytes(StandardCharsets.UTF_8);}
    private static JsonObject entry(String path,byte[] value){var item=new JsonObject();item.addProperty("path",path);item.addProperty("size",value.length);item.addProperty("sha256",BuiltinAssets.hash(value));return item;}
    private static byte[] verified(BuiltinAssets.Source source,Map<String,JsonObject> manifest,String path)throws IOException {
        var entry=manifest.get(path);if(entry==null)throw new IOException("清单缺少："+path);
        int size=entry.get("size").getAsInt();if(size<0||size>MAX)throw new IOException("资源长度超限："+path);
        var value=BuiltinAssets.read(source,path,size);
        if(value.length!=size||!BuiltinAssets.hash(value).equals(entry.get("sha256").getAsString()))throw new IOException("资源校验失败："+path);
        return value;
    }
    private static Map<String,byte[]> variant(BuiltinAssets.Source source,Map<String,JsonObject> manifest,String path)throws IOException {
        byte[] archive=verified(source,manifest,path);Map<String,byte[]> files=new HashMap<>();long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
                if(entry.isDirectory())continue;String name=entry.getName();
                if(!name.equals("index.json")&&!name.matches("models/tt_[a-z0-9_]+[.]bbmodel")
                        &&!name.matches("ce/resourcepack/assets/tongtou/(models/(doll/cube_[0-9]+|item/doll_legacy)|items/doll)[.]json"))throw new IOException("非法档案路径："+name);
                byte[] data=zip.readNBytes(MAX+1);total+=data.length;
                if(data.length>MAX||total>64L*1024*1024||files.size()>=4096||files.putIfAbsent(name,data)!=null)throw new IOException("档案超限或重复："+name);
            }
        }
        byte[] manifestBytes=files.remove("index.json");if(manifestBytes==null)throw new IOException("档案缺少清单");
        JsonObject index=json(manifestBytes);Set<String> found=new HashSet<>();
        if(index.get("schema").getAsInt()!=1||!index.get("source_minecraft").getAsString().equals("26.3"))throw new IOException("档案来源无效");
        for(var raw:index.getAsJsonArray("files")){
            var item=raw.getAsJsonObject();String name=item.get("path").getAsString();byte[] data=files.get(name);
            if(!found.add(name)||data==null||data.length!=item.get("size").getAsInt()||!BuiltinAssets.hash(data).equals(item.get("sha256").getAsString()))throw new IOException("档案资源校验失败："+name);
        }
        if(!found.equals(files.keySet()))throw new IOException("档案包含未声明文件");return files;
    }
    private static byte[] items(byte[] source,ServerProfile profile)throws IOException {
        try{
            var yaml=new YamlConfiguration();yaml.loadFromString(new String(source,StandardCharsets.UTF_8));
            String doll="items.tongtou:doll.",remote="items.tongtou:remote.";
            if(profile.legacyModels())yaml.set(doll+"behavior.furniture.variants.ground.blueprint",null);
            if(!profile.itemDefinitions()){
                yaml.set(doll+"data.item_model",null);yaml.set(doll+"model","tongtou:item/doll_legacy");
                yaml.set(remote+"data.item_model",null);yaml.set(remote+"model","tongtou:item/remote");
            }
            if(!profile.consumable()){
                yaml.set(remote+"data.components.minecraft:consumable",null);
                yaml.set(remote+"data.components.minecraft:food.nutrition",0);
                yaml.set(remote+"data.components.minecraft:food.saturation",0.0);
                yaml.set(remote+"data.components.minecraft:food.can_always_eat",true);
                yaml.set(remote+"data.components.minecraft:food.eat_seconds",3600.0);
            }
            return yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        }catch(Exception failure){throw new IOException("无法生成CE兼容物品定义",failure);}
    }
}
