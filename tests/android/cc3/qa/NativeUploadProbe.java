package cc3.qa;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import org.json.JSONObject;
/** Standalone emulator-only pinned HTTPS interoperability probe. Never packaged in Manager. */
public final class NativeUploadProbe {
    private static String fingerprint(X509Certificate cert)throws Exception{
        StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();
    }
    private static SSLContext context(String pin)throws Exception{
        final String expected=pin.replace(":","").toLowerCase(java.util.Locale.ROOT);
        X509TrustManager trust=new X509TrustManager(){
            @Override public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            @Override public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("No client trust");}
            @Override public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{
                try{if(c.length==0 || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),fingerprint(c[0]).getBytes(StandardCharsets.US_ASCII)))throw new CertificateException("Pin mismatch");c[0].checkValidity();}
                catch(CertificateException error){throw error;}catch(Exception error){throw new CertificateException(error);}
            }
        };
        SSLContext context=SSLContext.getInstance("TLSv1.3");context.init(null,new TrustManager[]{trust},new SecureRandom());return context;
    }
    private static HttpsURLConnection open(String base,String path,String pin,String cookie,String csrf,long length)throws Exception{
        HttpsURLConnection request=(HttpsURLConnection)new URL(base+path).openConnection();
        request.setSSLSocketFactory(context(pin).getSocketFactory());request.setInstanceFollowRedirects(false);request.setConnectTimeout(5000);request.setReadTimeout(10000);
        request.setRequestMethod("POST");request.setDoOutput(true);request.setFixedLengthStreamingMode(length);
        request.setRequestProperty("Origin",base);request.setRequestProperty("X-CC3-Upload","1");request.setRequestProperty("Content-Type","application/octet-stream");
        if(cookie!=null){request.setRequestProperty("Cookie",cookie);request.setRequestProperty("X-CC3-CSRF",csrf);}return request;
    }
    private static JSONObject read(HttpsURLConnection request)throws Exception{
        int code=request.getResponseCode();if(code!=200 && code!=201)throw new IOException("HTTP "+code);
        try(InputStream input=request.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>1048576)throw new IOException("Oversized response");output.write(buffer,0,n);}return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    public static void main(String[] args){
        try{probe(args);}catch(Exception error){error.printStackTrace(System.err);System.exit(1);}
    }
    private static void probe(String[] args)throws Exception{
        if(!android.os.Build.HARDWARE.toLowerCase(java.util.Locale.ROOT).contains("ranchu"))throw new SecurityException("Emulator only");
        byte[] input=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));JSONObject config=new JSONObject(new String(input,StandardCharsets.UTF_8));
        String base=config.getString("url"),pin=config.getString("fingerprint"),code=config.getString("code");
        JSONObject result=new JSONObject();HttpsURLConnection bad=open(base,"/api/pair",new String(new char[64]).replace('\0','0'),null,null,8);
        try{bad.getOutputStream();throw new AssertionError("Wrong pin accepted");}catch(SSLException expected){result.put("wrong_pin_rejected",true);}finally{bad.disconnect();}
        HttpsURLConnection pair=open(base,"/api/pair",pin,null,null,8);
        String cookie,csrf;
        try{try(OutputStream output=pair.getOutputStream()){output.write(code.getBytes(StandardCharsets.US_ASCII));}JSONObject reply=read(pair);String header=pair.getHeaderField("Set-Cookie");cookie=header.split(";",2)[0];csrf=reply.getString("csrf");result.put("paired",reply.getBoolean("paired"));result.put("secure_cookie",header.contains("Secure")&&header.contains("HttpOnly"));result.put("cipher",pair.getCipherSuite());}finally{pair.disconnect();}
        File file=new File(config.getString("fixture"));HttpsURLConnection upload=open(base,"/api/upload?name="+URLEncoder.encode(file.getName(),"UTF-8"),pin,cookie,csrf,file.length());
        try{try(InputStream source=new FileInputStream(file);OutputStream output=upload.getOutputStream()){byte[] buffer=new byte[65536];int n;while((n=source.read(buffer))!=-1)output.write(buffer,0,n);}JSONObject saved=read(upload);result.put("saved",saved.getBoolean("saved"));result.put("name",saved.getString("name"));result.put("bytes",saved.getLong("bytes"));}finally{upload.disconnect();}
        System.out.println(result.toString());
    }
}
