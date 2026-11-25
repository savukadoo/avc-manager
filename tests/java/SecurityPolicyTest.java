package local.cc3.manager;

import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;

/** Security boundaries, not vehicle or firmware fixtures. */
public final class SecurityPolicyTest {
    private static int checks;
    private static void require(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
    public static void main(String[] args)throws Exception{
        require(DebugLease.valid(100,1000,1,1,"wifi-a","wifi-a"),"valid same-network lease");
        require(!DebugLease.valid(1000,1000,1,1,"wifi-a","wifi-a"),"deadline is exclusive");
        require(!DebugLease.valid(100,1000,2,1,"wifi-a","wifi-a"),"reboot invalidates lease");
        require(!DebugLease.valid(100,1000,1,1,"wifi-b","wifi-a"),"replacement Wi-Fi invalidates lease");
        require(!DebugLease.valid(100,1000,1,1,"","wifi-a"),"disconnected invalidates lease");
        require(!DebugLease.valid(100,Long.MAX_VALUE,1,1,"wifi-a","wifi-a"),"tampered oversized lease rejected");
        require(!DebugLease.valid(-1,1000,1,1,"wifi-a","wifi-a"),"invalid monotonic clock rejected");
        require(FirewallRestartPolicy.requested(false,false,false,false,false,0)==0,"no unrequested boot protection");
        require(FirewallRestartPolicy.requested(false,false,false,true,true,0)==1,"persistent restarts enforcement only");
        require(FirewallRestartPolicy.requested(false,true,false,true,true,0)==0,"explicit stop wins over app persistence");
        require(FirewallRestartPolicy.requested(false,true,true,false,false,0)==1,"system always-on cannot be stopped in app");
        require(FirewallRestartPolicy.requested(true,false,false,false,false,2)==2,"capture can run independently");
        require(!FirewallRestartPolicy.keep(true,true,true,true),"revocation prevents retry");
        require(FirewallRestartPolicy.keep(false,true,true,false),"explicit protection restarts");
        StringBuilder csv=new StringBuilder();VehicleReport.csvRow(csv,"Connection","Pressure","-1.25","a\"b","line\nbreak","=2+3","+cmd()","@call()","\u202Ehidden");
        require(csv.toString().contains("\"Connection\",\"Pressure\",\"-1.25\""),"text and signed values preserved");
        require(csv.toString().contains("\"a\"\"b\""),"CSV quoting escapes double quote");
        require(csv.toString().contains("line break") && csv.indexOf("\n")==csv.length()-1 && csv.lastIndexOf("\n")==csv.indexOf("\n"),"CSV record termination");
        require(csv.toString().contains("'=" ) && csv.toString().contains("'+cmd") && csv.toString().contains("'@call"),"spreadsheet expressions neutralized");
        require(!csv.toString().contains("\u202E"),"format control removed");
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
        X509Certificate certificate=TlsCertificate.issue(pair,InetAddress.getByName("127.0.0.1"));
        certificate.verify(pair.getPublic());certificate.checkValidity();checks++;
        require(certificate.getSigAlgName().equalsIgnoreCase("SHA256withRSA"),"SHA256 certificate signature");
        require(certificate.getBasicConstraints()==-1,"upload identity is not a CA");
        require(certificate.getSubjectAlternativeNames().stream().anyMatch(v->Integer.valueOf(7).equals(v.get(0)) && "127.0.0.1".equals(v.get(1))),"IP SAN matches endpoint");
        require(TlsCertificate.fingerprint(certificate).matches("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}"),"full SHA256 pin shown");
        KeyPairGenerator ec=KeyPairGenerator.getInstance("EC");ec.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));KeyPair ecPair=ec.generateKeyPair();
        X509Certificate ecCertificate=TlsCertificate.issue(ecPair,InetAddress.getByName("127.0.0.1"));ecCertificate.verify(ecPair.getPublic());ecCertificate.checkValidity();checks++;
        require(ecCertificate.getSigAlgName().equalsIgnoreCase("SHA256withECDSA"),"P256 ECDSA TLS certificate signature");
        System.out.println(checks+" security policy and certificate checks passed");
    }
}
