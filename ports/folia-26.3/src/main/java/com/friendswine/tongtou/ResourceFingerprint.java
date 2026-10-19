package com.friendswine.tongtou;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** 合包去重同时包含实际CE配置与声音，不能只看BetterModel几何hash。 */
final class ResourceFingerprint {
    private ResourceFingerprint() {}
    static String combine(String modelHash,Path root)throws IOException {
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(modelHash.getBytes(StandardCharsets.UTF_8));
            try(var paths=Files.walk(root)){
                for(Path path:paths.sorted().toList()){
                    if(Files.isSymbolicLink(path))throw new IOException("CE资源包含符号链接："+path);
                    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                    digest.update(("\0"+root.relativize(path).toString().replace(File.separatorChar,'/')+"\0").getBytes(StandardCharsets.UTF_8));
                    digest.update(Long.toString(Files.size(path)).getBytes(StandardCharsets.UTF_8));
                    try(var input=Files.newInputStream(path)){
                        byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))>=0;)digest.update(buffer,0,n);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
