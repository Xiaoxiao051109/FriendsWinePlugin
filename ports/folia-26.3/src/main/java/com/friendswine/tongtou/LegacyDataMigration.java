package com.friendswine.tongtou;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** 改名仅复制本插件已知数据，保留旧目录，不覆盖新目录中的管理员配置。 */
final class LegacyDataMigration {
    private static final String MARKER=".legacy-tongtou-migrated";
    private static final List<String> FILES=List.of("config.yml","catalog.json","coverage.json","feature-models.json",
            "music.json","party-clock.txt",".installed-builtin.properties");
    private LegacyDataMigration() {}
    static int migrate(Path legacy,Path current)throws IOException {
        Path marker=BuiltinAssets.safe(current,MARKER);
        if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS))throw new IOException("迁移标记不是普通文件");
            return 0;
        }
        // safe同时检查父目录中的符号链接，不能把旧路径当作任意文件复制入口。
        BuiltinAssets.safe(legacy,"config.yml");
        if(!Files.exists(legacy,LinkOption.NOFOLLOW_LINKS))return 0;
        if(!Files.isDirectory(legacy,LinkOption.NOFOLLOW_LINKS))throw new IOException("旧TongtouMode数据目录不是普通目录");
        int copied=0;
        for(String name:FILES){
            Path source=BuiltinAssets.safe(legacy,name),target=BuiltinAssets.safe(current,name);
            if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)>16*1024*1024)
                throw new IOException("拒绝迁移异常旧数据文件："+name);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
                if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("迁移目标不是普通文件："+name);
                continue;
            }
            Files.createDirectories(target.getParent());
            Path temporary=Files.createTempFile(target.getParent(),".friendswine-migrate-",".tmp");
            try{Files.copy(source,temporary,StandardCopyOption.REPLACE_EXISTING);Files.move(temporary,target);copied++;}
            finally{Files.deleteIfExists(temporary);}
        }
        Files.createDirectories(marker.getParent());
        Files.writeString(marker,"Copied known data from TongtouMode; original directory retained.\n",StandardOpenOption.CREATE_NEW);
        return copied;
    }
}
