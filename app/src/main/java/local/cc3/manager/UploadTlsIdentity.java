package local.cc3.manager;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.nio.file.Files;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.SSLEngine;

/** Per-device, non-exportable TLS key; only the public certificate is cached on disk. */
final class UploadTlsIdentity {
    final SSLContext context;
    final String fingerprint;
    private static final String ALIAS="cc3-media-https-v2";
    static synchronized UploadTlsIdentity load(Context app, InetAddress address) throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (!keys.containsAlias(ALIAS)) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC","AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                    // Conscrypt on Android10 signs its already-computed TLS digest via NONEwithECDSA.
                    .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        KeyStore.PrivateKeyEntry key=(KeyStore.PrivateKeyEntry)keys.getEntry(ALIAS,null);
        X509Certificate certificate=null;
        File cached=new File(app.getFilesDir(),"media-https-public.der");
        if(cached.isFile() && cached.length()<16384) try(java.io.InputStream in=Files.newInputStream(cached.toPath())) {
            X509Certificate previous=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);
            previous.checkValidity(); previous.verify(key.getCertificate().getPublicKey());
            if(previous.getSubjectAlternativeNames()!=null) for(java.util.List<?> san:previous.getSubjectAlternativeNames())
                if(Integer.valueOf(7).equals(san.get(0)) && address.getHostAddress().equals(san.get(1))) certificate=previous;
        } catch(Exception invalid) { Diagnostics.event("Uploads","CertificateRefresh",invalid); }
        if(certificate==null) {
            certificate=TlsCertificate.issue(new KeyPair(key.getCertificate().getPublicKey(),key.getPrivateKey()),address);
            File temporary=new File(app.getFilesDir(),"media-https-public.tmp");
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(temporary)) {out.write(certificate.getEncoded());out.getFD().sync();}
            Files.move(temporary.toPath(),cached.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return new UploadTlsIdentity(key.getPrivateKey(),certificate);
    }
    private UploadTlsIdentity(PrivateKey key, X509Certificate certificate) throws Exception {
        fingerprint=TlsCertificate.fingerprint(certificate);
        X509ExtendedKeyManager manager=new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String t,Principal[] p){return null;}
            @Override public String chooseClientAlias(String[] t,Principal[] p,Socket s){return null;}
            @Override public String[] getServerAliases(String t,Principal[] p){return "EC".equals(t)?new String[]{ALIAS}:null;}
            @Override public String chooseServerAlias(String t,Principal[] p,Socket s){return "EC".equals(t)?ALIAS:null;}
            @Override public String chooseEngineServerAlias(String t,Principal[] p,SSLEngine e){return "EC".equals(t)?ALIAS:null;}
            @Override public X509Certificate[] getCertificateChain(String alias){return ALIAS.equals(alias)?new X509Certificate[]{certificate}:null;}
            @Override public PrivateKey getPrivateKey(String alias){return ALIAS.equals(alias)?key:null;}
        };
        context=SSLContext.getInstance("TLSv1.3");context.init(new KeyManager[]{manager},null,new SecureRandom());
    }
}
