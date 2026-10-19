package com.friendswine.tongtou;

import java.nio.file.Path;
import java.util.List;
import java.util.function.*;
import org.bukkit.entity.Entity;

/** 公共玩法不解析任何一代BetterModel类型，具体后端按能力惰性加载。 */
public interface ModelBackend {
    interface Handle {
        String model();
        boolean closed();
        boolean temporary();
        boolean same(Handle other);
        boolean once(String name,AnimationSettings settings);
        boolean continuous(String name,Supplier<DollSnapshot> source,DoubleSupplier clock,Supplier<AnimationSettings> settings,boolean feetAnchor);
        void stop(String name);
        void prime();
        void removeOwned();
    }
    record Reload(Path pack,String hash,Throwable failure,boolean busy) {
        public static Reload failed(Throwable failure){return new Reload(null,null,failure,false);}
        public static Reload loading(){return new Reload(null,null,null,true);}
    }
    boolean hasModel(String name);
    List<Handle> existing(Entity entity);
    Handle find(Entity entity,String name);
    Handle attach(Entity entity,String name,boolean temporary,Consumer<Handle> prepare);
    AutoCloseable subscribe(BooleanSupplier enabled,Runnable starting,Consumer<Reload> completed);
    Reload reload();
}
