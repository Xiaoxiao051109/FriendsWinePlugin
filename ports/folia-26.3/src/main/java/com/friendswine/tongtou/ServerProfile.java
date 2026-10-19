package com.friendswine.tongtou;

import java.util.*;
import java.util.regex.*;

/** 服务器能力与资源档案快照；统一26.3源模型，不将未来版本写成已验证。 */
public record ServerProfile(String minecraft,int line,int patch,boolean legacyModels,boolean itemDefinitions,boolean consumable) {
    public static ServerProfile select(String minecraft,String betterModel,int java){
        Matcher m=Pattern.compile("^(1[.]21|26[.][123])(?:[.]([0-9]+))?(?:[-+].*)?$").matcher(minecraft);
        if(!m.matches())throw new IllegalArgumentException("支持已发布的1.21.x与26.1至26.3，当前："+minecraft);
        boolean old=m.group(1).equals("1.21");int patch=m.group(2)==null?0:Integer.parseInt(m.group(2));
        if(old&&patch>11)throw new IllegalArgumentException("尚未验证的1.21补丁版本："+minecraft);
        boolean legacy=betterModel.equals("1.15.2");
        if(!legacy&&!betterModel.matches("3[.]5[.][0-9]+(?:[-+].*)?"))
            throw new IllegalArgumentException("BetterModel需1.15.2或3.5.x，当前："+betterModel);
        if(legacy&&!old)throw new IllegalArgumentException("26.x需BetterModel3.5.x，不能使用1.15.2");
        if(!legacy&&old&&patch<4)throw new IllegalArgumentException("1.21至1.21.3请使用BetterModel1.15.2");
        if(java<(legacy?21:25))throw new IllegalArgumentException("当前BetterModel要求Java"+(legacy?21:25));
        return new ServerProfile(minecraft,old?121:Integer.parseInt(m.group(1).substring(3)),patch,legacy,!old||patch>=4,!old||patch>=2);
    }
    public String resourceKey(){return legacyModels?"bm1":"bm3";}
}
