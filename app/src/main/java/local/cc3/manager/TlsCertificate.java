package local.cc3.manager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;

/** Minimal fixed-purpose X.509 issuer; no imported ASN.1, algorithms or executable expressions. */
final class TlsCertificate {
    static X509Certificate issue(KeyPair pair, InetAddress address) throws Exception {
        boolean ec="EC".equals(pair.getPublic().getAlgorithm());
        byte[] algorithm = ec?sequence(oid("1.2.840.10045.4.3.2")):sequence(oid("1.2.840.113549.1.1.11"), tag(5));
        byte[] name = sequence(tag(0x31, sequence(oid("2.5.4.3"),
                tag(0x0c, "CC3 local uploads".getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = sequence(tag(0x17, ascii("200101000000Z")), tag(0x17, ascii("450101000000Z")));
        byte[] extensions = tag(0xa3, sequence(
                sequence(oid("2.5.29.17"), tag(4, sequence(tag(0x87, address.getAddress())))),
                sequence(oid("2.5.29.19"), tag(1, new byte[]{(byte)255}), tag(4, sequence())),
                sequence(oid("2.5.29.37"), tag(4, sequence(oid("1.3.6.1.5.5.7.3.1"))))));
        byte[] tbs = sequence(tag(0xa0, tag(2, new byte[]{2})),
                tag(2, new BigInteger(159, new SecureRandom()).add(BigInteger.ONE).toByteArray()),
                algorithm, name, validity, name, pair.getPublic().getEncoded(), extensions);
        Signature signer = Signature.getInstance(ec?"SHA256withECDSA":"SHA256withRSA");
        signer.initSign(pair.getPrivate()); signer.update(tbs);
        byte[] encoded = sequence(tbs, algorithm, tag(3, new byte[]{0}, signer.sign()));
        X509Certificate result = (X509Certificate)CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encoded));
        result.verify(pair.getPublic());
        return result;
    }
    static String fingerprint(X509Certificate certificate) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())) {
            if (value.length()>0) value.append(':');
            value.append(String.format(Locale.ROOT,"%02X",b&255));
        }
        return value.toString();
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] sequence(byte[]... values) { return tag(0x30,values); }
    private static byte[] tag(int kind, byte[]... values) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] value:values) content.write(value,0,value.length);
        byte[] body=content.toByteArray(); ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(kind);
        if (body.length<128) out.write(body.length);
        else {
            int bytes=0; for(int length=body.length;length>0;length>>>=8) bytes++;
            out.write(0x80|bytes);
            for(int shift=(bytes-1)*8;shift>=0;shift-=8) out.write(body.length>>>shift&255);
        }
        out.write(body,0,body.length); return out.toByteArray();
    }
    private static byte[] oid(String value) {
        String[] words=value.split("\\."); ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(Integer.parseInt(words[0])*40+Integer.parseInt(words[1]));
        for(int i=2;i<words.length;i++) {
            long part=Long.parseLong(words[i]); int bytes=1;
            for(long n=part;n>=128;n>>>=7) bytes++;
            for(int shift=(bytes-1)*7;shift>=0;shift-=7) out.write((int)(part>>>shift&127)|(shift==0?0:128));
        }
        return tag(6,out.toByteArray());
    }
    private TlsCertificate() { }
}
