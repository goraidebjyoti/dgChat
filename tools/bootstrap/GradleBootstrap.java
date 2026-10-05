package io.github.goraidebjyoti.dgchat.build;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
/** Independent minimal bootstrap, not the official Gradle wrapper. CI generates the official wrapper. */
public final class GradleBootstrap {
    static final String VERSION="8.11.1",SHA="f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6";
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("--bootstrap-info")){System.out.println("dgChat Gradle bootstrap "+VERSION+" sha256="+SHA);return;}
        String configured=System.getenv("GRADLE_USER_HOME");
        Path base=Paths.get(configured==null?System.getProperty("user.home")+"/.gradle":configured,"dgchat-bootstrap",VERSION);
        Files.createDirectories(base);
        boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path executable=base.resolve("gradle-"+VERSION+"/bin/gradle"+(windows?".bat":""));
        if(!Files.isRegularFile(executable)) {
            Path archive=base.resolve("gradle.zip");
            URLConnection connection=new URL("https://services.gradle.org/distributions/gradle-"+VERSION+"-bin.zip").openConnection();
            connection.setConnectTimeout(30000);connection.setReadTimeout(30000);
            try(InputStream in=connection.getInputStream()){Files.copy(in,archive,StandardCopyOption.REPLACE_EXISTING);}
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(archive)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!hex.toString().equals(SHA)){Files.deleteIfExists(archive);throw new SecurityException("Gradle distribution checksum mismatch");}
            try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive))){ZipEntry e;long total=0;
                while((e=zip.getNextEntry())!=null){Path out=base.resolve(e.getName()).normalize();if(!out.startsWith(base))throw new SecurityException("zip path");
                    if(e.isDirectory()){Files.createDirectories(out);continue;}Files.createDirectories(out.getParent());
                    try(OutputStream target=Files.newOutputStream(out)){byte[] buffer=new byte[32768];int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>1000000000L)throw new IOException("distribution size limit");target.write(buffer,0,n);}}
                }
            }Files.deleteIfExists(archive);if(!windows)executable.toFile().setExecutable(true,false);
        }
        List<String> command=new ArrayList<>();if(windows){command.add("cmd");command.add("/c");}command.add(executable.toString());command.addAll(Arrays.asList(args));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
}
