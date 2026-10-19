package com.friendswine.tongtou;

/** 不在公共类签名、静态字段或服务扫描中提前链接未选中的后端。 */
final class ModelBackends {
    private ModelBackends() {}
    static ModelBackend create(FriendsWinePlugin plugin,ServerProfile profile){
        String name="com.friendswine.tongtou."+(profile.legacyModels()?"LegacyModelBackend":"ModernModelBackend");
        try{return (ModelBackend)Class.forName(name,true,plugin.getClass().getClassLoader()).getConstructor(FriendsWinePlugin.class).newInstance(plugin);}
        catch(ReflectiveOperationException|LinkageError failure){throw new IllegalStateException("无法加载模型后端："+profile.resourceKey(),failure);}
    }
}
