package com.friendswine.tongtou;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 页边界偏移是近似续播，不声称原版协议支持采样级seek。 */
public final class MusicTimeline {
    public record Offset(int id,long samples) {}
    public record Cue(long loop,int offset) {}
    private final int rate,slots;
    private final long frames;
    private final List<Offset> offsets;
    private MusicTimeline(int rate,int slots,long frames,List<Offset> offsets){
        this.rate=rate;this.slots=slots;this.frames=frames;this.offsets=List.copyOf(offsets);
    }
    public static MusicTimeline load(InputStream stream) throws IOException {
        if(stream==null)throw new IOException("缺少music.json");
        try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){
            var data=JsonParser.parseReader(reader).getAsJsonObject();
            int rate=data.get("sample_rate").getAsInt(),slots=data.get("slots").getAsInt();long frames=data.get("frames").getAsLong();
            if(data.get("schema").getAsInt()!=1||rate!=44100||slots!=4||frames!=2482560)throw new IOException("音乐参数不匹配");
            List<Offset> offsets=new ArrayList<>();long previous=-1;
            for(var raw:data.getAsJsonArray("offsets")){
                var entry=raw.getAsJsonObject();int id=entry.get("id").getAsInt();long samples=entry.get("offset_samples").getAsLong();
                if(id!=offsets.size()||samples<=previous||samples>=frames||samples<0)throw new IOException("音乐偏移顺序无效");
                offsets.add(new Offset(id,samples));previous=samples;
            }
            if(offsets.isEmpty()||offsets.getFirst().samples()!=0)throw new IOException("音乐缺少起始段");
            return new MusicTimeline(rate,slots,frames,offsets);
        }catch(RuntimeException malformed){throw new IOException("音乐清单无效",malformed);}
    }
    public int slots(){return slots;}
    public double duration(){return (double)frames/rate;}
    public Cue cue(double seconds){
        double sample=Math.max(0,seconds)*rate;long loop=(long)Math.floor(sample/frames);
        double phase=sample-loop*frames;int selected=0;
        for(var offset:offsets){if(offset.samples()>phase)break;selected=offset.id();}
        return new Cue(loop,selected);
    }
}
