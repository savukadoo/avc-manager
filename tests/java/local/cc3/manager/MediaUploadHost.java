package local.cc3.manager;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
/** Host-only adapter for real HTTP/socket boundary tests. Never packaged in the APK. */
public final class MediaUploadHost {
    public static void main(String[] args)throws Exception {
        File root=new File(args[0]);AtomicLong clock=new AtomicLong(System.currentTimeMillis());
        javax.net.ssl.SSLServerSocketFactory tls=null;String fingerprint="";
        if(args.length>4 && (args[4].equals("tls") || args[4].equals("tls-ec"))){
            java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance(args[4].equals("tls-ec")?"EC":"RSA");if(args[4].equals("tls-ec"))generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));else generator.initialize(2048);
            java.security.KeyPair pair=generator.generateKeyPair();java.security.cert.X509Certificate certificate=TlsCertificate.issue(pair,InetAddress.getByName("127.0.0.1"));
            java.security.KeyStore keys=java.security.KeyStore.getInstance("PKCS12");keys.load(null,null);
            keys.setKeyEntry("test",pair.getPrivate(),new char[0],new java.security.cert.Certificate[]{certificate});
            javax.net.ssl.KeyManagerFactory managers=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,new char[0]);
            javax.net.ssl.SSLContext context=javax.net.ssl.SSLContext.getInstance("TLS");context.init(managers.getKeyManagers(),null,null);tls=context.getServerSocketFactory();
            fingerprint=TlsCertificate.fingerprint(certificate);
        }
        MediaUploadServer server=new MediaUploadServer(root,(Inet4Address)InetAddress.getByName("127.0.0.1"),8,
                Files.readAllBytes(Paths.get(args[1])),name->name.toLowerCase().matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|oga|opus|amr|mp4|m4v|mkv|webm|3gp|3gpp|ts|m2ts)$"),
                new MediaUploadServer.Listener(){public void progress(String name,long got,long total){}public void saved(File f){}public void interrupted(String m){}public void stopped(){}},clock::get,
                args.length>2?Long.parseLong(args[2]):0,tls);
        server.start(args.length>3?Integer.parseInt(args[3]):0);System.out.println(server.url()+" "+server.pairingCode()+(fingerprint.isEmpty()?"":" "+fingerprint));System.out.flush();
        BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));String line;
        while((line=reader.readLine())!=null){if(line.equals("stop"))break;if(line.startsWith("advance "))clock.addAndGet(Long.parseLong(line.substring(8)));}
        server.close();
    }
}
