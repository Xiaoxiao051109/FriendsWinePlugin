package com.friendswine.tongtou;

import org.bukkit.configuration.ConfigurationSection;

/** 配置重载先校验再一次发布，区域线程不读取正在替换的YAML对象。 */
public record PartySettings(boolean peace,int maxDolls,int maxTracked,int maxVisual,
                            double orbitSpeed,boolean music,int soundSources,boolean animateExisting) {
    public PartySettings {
        if(maxDolls<1||maxDolls>4096||maxTracked<1||maxTracked>65536||maxVisual<1||maxVisual>8192
                ||!Double.isFinite(orbitSpeed)||orbitSpeed<0||orbitSpeed>10||soundSources<1||soundSources>4)
            throw new IllegalArgumentException("party配置超出范围：玩偶1..4096、跟踪1..65536、显示1..8192、绕圈0..10、音源1..4");
    }
    public static PartySettings load(ConfigurationSection config){
        return new PartySettings(config.getBoolean("party.peace",true),config.getInt("party.max-dolls",128),
                config.getInt("party.max-tracked-mobs",8192),config.getInt("party.max-visual-entities",256),
                config.getDouble("party.orbit-speed",1),config.getBoolean("party.music.enabled",true),
                config.getInt("party.music.max-sources-per-player",4),config.getBoolean("animate-existing-models",true));
    }
}
