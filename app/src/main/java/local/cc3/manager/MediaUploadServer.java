package local.cc3.manager;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** Short-lived, explicit-interface LAN upload server. No media downloads or arbitrary file paths. */
public final class MediaUploadServer implements Closeable {
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024 * 1024;
    public static final long RESERVE_BYTES = 512L * 1024 * 1024;
    public static final long IDLE_MILLIS = 10 * 60 * 1000, MAX_MILLIS = 2 * 60 * 60 * 1000;
    public interface Listener {
        void progress(String name, long received, long total);
        void saved(File file);
        void interrupted(String message);
        void stopped();
    }
    private final File root;
    private final Inet4Address address;
    private final int prefix;
    private final byte[] page;
    private final Predicate<String> accepted;
    private final Listener listener;
    private final LongSupplier clock;
    private final long reserve;
    private final String pin;
    private final SecureRandom random = new SecureRandom();
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final AtomicBoolean uploading = new AtomicBoolean(), notified = new AtomicBoolean();
    private final ExecutorService workers = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), job -> { Thread thread = new Thread(job, "cc3-media-upload"); thread.setDaemon(true); return thread; });
    // Reserved capacity for peers that already paired; cookies/CSRF remain mandatory per request.
    private final ExecutorService pairedWorkers = new ThreadPoolExecutor(2,2,0L,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),job->{Thread thread=new Thread(job,"cc3-paired-upload");thread.setDaemon(true);return thread;});
    private final SSLServerSocketFactory tls;
    private final Map<String, Integer> pendingPeers = new HashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(job -> {
        Thread thread = new Thread(job, "cc3-upload-deadlines"); thread.setDaemon(true); return thread;
    });
    private static final long HEADER_MILLIS = 3000;
    private static final int MAX_PENDING_PER_PEER = 2;
    private ServerSocket server;
    private volatile boolean running;
    private volatile long started, lastActivity;
    private long attemptsSince;
    private int attempts;

    private static final class Session {
        final String peer, csrf;
        Session(String peer, String csrf) { this.peer = peer; this.csrf = csrf; }
    }
    private static final class Request {
        String method, path;
        final Map<String, String> headers = new LinkedHashMap<>();
        long length;
    }
    private static final class Problem extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        Problem(int status, String message) { super(message); this.status = status; }
    }

    public MediaUploadServer(File root, Inet4Address address, int prefix, byte[] page,
                             Predicate<String> accepted, Listener listener) {
        this(root,address,prefix,page,accepted,listener,System::currentTimeMillis,RESERVE_BYTES,null);
    }
    MediaUploadServer(File root, Inet4Address address, int prefix, byte[] page,
                      Predicate<String> accepted, Listener listener, LongSupplier clock, long reserve) {
        this(root,address,prefix,page,accepted,listener,clock,reserve,null);
    }
    public MediaUploadServer(File root, Inet4Address address, int prefix, byte[] page,
            Predicate<String> accepted, Listener listener, SSLServerSocketFactory tls) {
        this(root,address,prefix,page,accepted,listener,System::currentTimeMillis,RESERVE_BYTES,
                Objects.requireNonNull(tls,"TLS factory"));
    }
    MediaUploadServer(File root, Inet4Address address, int prefix, byte[] page,
            Predicate<String> accepted, Listener listener, LongSupplier clock, long reserve,
            SSLServerSocketFactory tls) {
        if (tls == null && !address.isLoopbackAddress())
            throw new IllegalArgumentException("Network uploads require TLS");
        this.tls = tls;
        if ((!address.isSiteLocalAddress() && !address.isLoopbackAddress()) || prefix < 8 || prefix > 32)
            throw new IllegalArgumentException("Choose a private local IPv4 interface");
        this.root = root; this.address = address; this.prefix = prefix; this.page = page.clone();
        this.accepted = accepted; this.listener = listener; this.clock = clock; this.reserve = Math.max(0,reserve);
        pin = String.format(Locale.ROOT,"%08d", random.nextInt(100000000));
    }
    public synchronized void start(int port) throws IOException {
        if (server != null) throw new IOException("Upload server cannot be reused");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("CC3 media storage is unavailable");
        // Allow immediate Stop/Start on the same port after completed TCP connections.
        // A second live listener still cannot bind this interface and port.
        server = tls == null ? new ServerSocket() : tls.createServerSocket();
        if (server instanceof SSLServerSocket) {
            SSLServerSocket secure = (SSLServerSocket) server;
            secure.setEnabledCipherSuites(Arrays.stream(secure.getSupportedCipherSuites())
                    .filter(value->value.startsWith("TLS_AES_") || value.startsWith("TLS_CHACHA20_")
                            || value.contains("_ECDHE_") && (value.contains("_GCM_") || value.contains("_CHACHA20_"))).toArray(String[]::new));
            secure.setEnabledProtocols(Arrays.stream(secure.getSupportedProtocols())
                    .filter(value -> value.equals("TLSv1.2") || value.equals("TLSv1.3")).toArray(String[]::new));
        }
        server.setReuseAddress(true);
        try { server.bind(new InetSocketAddress(address,port),8); server.setSoTimeout(1000); }
        catch (IOException error) { server.close(); throw error; }
        started = lastActivity = clock.getAsLong(); running = true;
        Thread acceptor = new Thread(this::accept,"cc3-media-listener"); acceptor.setDaemon(true); acceptor.start();
    }
    public String address() { return address.getHostAddress(); }
    public synchronized int port() { return server == null ? 0 : server.getLocalPort(); }
    public String url() { return (tls == null ? "http://" : "https://") + address() + ":" + port(); }
    public String pairingCode() { return pin; }
    public boolean active() { return running; }
    public boolean busy() { return uploading.get(); }
    public long usableBytes() { return Math.max(0, root.getUsableSpace() - reserve); }
    public boolean expired() {
        long now = clock.getAsLong();
        return now < started || now - started >= MAX_MILLIS || !uploading.get() && now - lastActivity >= IDLE_MILLIS;
    }
    private void accept() {
        try {
            while (running && !expired()) {
                try {
                    Socket socket = server.accept();
                    if (!localPeer(socket.getInetAddress())) { socket.close(); continue; }
                    String peer = socket.getInetAddress().getHostAddress();
                    synchronized (pendingPeers) {
                        int pending = pendingPeers.getOrDefault(peer,0);
                        if (pending >= MAX_PENDING_PER_PEER) { socket.close(); continue; }
                        pendingPeers.put(peer,pending+1);
                    }
                    socket.setSoTimeout((int)HEADER_MILLIS); sockets.add(socket);
                    AtomicBoolean pending = new AtomicBoolean(true);
                    Runnable release = () -> {
                        if (pending.compareAndSet(true,false)) synchronized(pendingPeers) {
                            int count=pendingPeers.getOrDefault(peer,1)-1;
                            if(count==0)pendingPeers.remove(peer);else pendingPeers.put(peer,count);
                        }
                    };
                    ScheduledFuture<?> limit = deadlines.schedule(() -> {
                        if (pending.get()) try { socket.close(); } catch(IOException ignored) { }
                    },HEADER_MILLIS,TimeUnit.MILLISECONDS);
                    boolean knownPeer;
                    synchronized(sessions){knownPeer=sessions.values().stream().anyMatch(session->session.peer.equals(peer));}
                    try { (knownPeer?pairedWorkers:workers).execute(() -> handle(socket,limit,release)); }
                    catch (RejectedExecutionException busy) {
                        limit.cancel(false);release.run();sockets.remove(socket);socket.close();
                    }
                } catch (SocketTimeoutException idle) { /* Recheck expiry and explicit Stop. */ }
            }
        } catch (IOException ignored) { /* Closing the listener cancels accept. */ }
        finally { close(); }
    }
    private boolean localPeer(InetAddress peer) {
        if (!(peer instanceof Inet4Address)) return false;
        byte[] remote=peer.getAddress(), own=address.getAddress();
        for (int bit=0;bit<prefix;bit++) if ((remote[bit/8] & (0x80 >> (bit%8))) != (own[bit/8] & (0x80 >> (bit%8)))) return false;
        return true;
    }
    private void handle(Socket socket, ScheduledFuture<?> limit, Runnable release) {
        try (Socket client = socket) {
            if (client instanceof SSLSocket) ((SSLSocket)client).startHandshake();
            InputStream in = new BufferedInputStream(client.getInputStream(),65536);
            OutputStream out = client.getOutputStream();
            try {
                Request request = request(in);
                if (!running || expired()) throw new Problem(410,"Upload session has ended. Start a new session on the CC3.");
                String host = address() + ":" + port();
                if (!host.equals(request.headers.get("host"))) throw new Problem(403,"Use the local address shown on the CC3.");
                if (request.method.equals("GET") && request.path.equals("/")) {
                    response(out,200,"text/html; charset=utf-8",page,Collections.emptyMap()); return;
                }
                String peer = client.getInetAddress().getHostAddress();
                if (request.method.equals("POST")) {
                    if (!url().equals(request.headers.get("origin")) || !"1".equals(request.headers.get("x-cc3-upload")))
                        throw new Problem(403,"Open the upload page directly and pair first.");
                }
                if (request.method.equals("POST") && request.path.equals("/api/pair")) {
                    if (request.length < 1 || request.length > 32) throw new Problem(400,"Enter the eight-digit pairing code.");
                    synchronized (sessions) {
                        long now=clock.getAsLong();
                        if (now - attemptsSince >= 60000 || now < attemptsSince) { attempts=0;attemptsSince=now; }
                        if (attempts >= 10) throw new Problem(429,"Too many pairing attempts. Wait one minute.");
                        attempts++;
                    }
                    String code = new String(read(in,(int)request.length),StandardCharsets.US_ASCII).trim();
                    if (!MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII),code.getBytes(StandardCharsets.US_ASCII)))
                        throw new Problem(401,"The pairing code did not match.");
                    String token = token(), csrf = token();
                    synchronized (sessions) {
                        while(sessions.size()>=8) sessions.remove(sessions.keySet().iterator().next());
                        sessions.put(token,new Session(peer,csrf));
                    }
                    lastActivity=clock.getAsLong();
                    json(out,200,"{\"paired\":true,\"csrf\":"+quote(csrf)+"}",
                            Collections.singletonMap("Set-Cookie","cc3media="+token+"; Path=/; HttpOnly; SameSite=Strict"+(tls==null?"":"; Secure"))); return;
                }
                Session session = session(request,peer);
                if (session == null) throw new Problem(401,"Pair with the code shown on the CC3.");
                if (request.method.equals("GET") && request.path.equals("/api/status")) {
                    json(out,200,"{\"paired\":true,\"csrf\":"+quote(session.csrf)+",\"availableBytes\":"+usableBytes()
                            +",\"maxFileBytes\":"+Math.min(MAX_FILE_BYTES,usableBytes())+",\"busy\":"+busy()+"}",Collections.emptyMap()); return;
                }
                if (request.method.equals("POST") && request.path.startsWith("/api/upload?name=")) {
                    if (!MessageDigest.isEqual(session.csrf.getBytes(StandardCharsets.US_ASCII),
                            request.headers.getOrDefault("x-cc3-csrf","").getBytes(StandardCharsets.US_ASCII)))
                        throw new Problem(403,"Refresh the page and pair again.");
                    // Only an authenticated, CSRF-checked upload may outlive the header deadline.
                    limit.cancel(false);release.run();client.setSoTimeout(20000);
                    upload(request,in,out); return;
                }
                throw new Problem(404,"This local server only accepts media uploads.");
            } catch (Problem problem) {
                json(out,problem.status,"{\"error\":"+quote(problem.getMessage())+"}",Collections.emptyMap());
            }
        } catch (IOException ignored) { /* A cancelled browser upload does not stop the server. */ }
        finally { limit.cancel(false);release.run();sockets.remove(socket); }
    }
    private void upload(Request request, InputStream in, OutputStream out) throws IOException {
        String name;
        try { name = MediaFileStore.safeName(decode(request.path.substring("/api/upload?name=".length()))); }
        catch (IOException invalid) { throw new Problem(400,invalid.getMessage()); }
        if (!accepted.test(name)) throw new Problem(415,"Choose one of the supported audio or video file extensions.");
        if (request.length <= 0) throw new Problem(400,"Empty files cannot be uploaded.");
        if (request.length > MAX_FILE_BYTES) throw new Problem(413,"Files must be 16 GiB or smaller.");
        if (request.length > usableBytes()) throw new Problem(507,"There is not enough free CC3 storage for this file.");
        if (!uploading.compareAndSet(false,true)) throw new Problem(409,"Another file is uploading. Wait for it to finish.");
        File temporary = null;
        boolean saved = false;
        try {
            if (request.length > usableBytes()) throw new Problem(507,"There is not enough free CC3 storage for this file.");
            temporary = MediaFileStore.incoming(root,"upload");
            if ("100-continue".equalsIgnoreCase(request.headers.get("expect"))) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();
            }
            byte[] buffer = new byte[65536];long received=0, lastCheck=0;
            listener.progress(name,0,request.length);
            try (FileOutputStream file = new FileOutputStream(temporary)) {
                while (received < request.length) {
                    if (!running || clock.getAsLong()-started>=MAX_MILLIS) throw new IOException("Upload session ended");
                    int count = in.read(buffer,0,(int)Math.min(buffer.length,request.length-received));
                    if (count < 0) throw new EOFException("Connection ended before the file finished");
                    if (count == 0) continue;
                    if (received-lastCheck>=1048576 || received==0) {
                        if (root.getUsableSpace()-count < reserve) throw new Problem(507,"CC3 storage is full. The incomplete file was removed.");
                        lastCheck=received;
                    }
                    file.write(buffer,0,count);received+=count;lastActivity=clock.getAsLong();
                    listener.progress(name,received,request.length);
                }
                file.getFD().sync();
            }
            File completed;
            synchronized(this) {
                if (!running || expired()) throw new IOException("Upload session stopped");
                completed = MediaFileStore.commit(root,temporary,name);saved=true;
            }
            listener.saved(completed);
            json(out,201,"{\"saved\":true,\"name\":"+quote(completed.getName())+",\"bytes\":"+completed.length()+"}",Collections.emptyMap());
        } catch (IOException error) {
            listener.interrupted(error instanceof Problem ? error.getMessage() : "Upload interrupted. Incomplete file removed; retry from the laptop.");
            throw error;
        } finally {
            if (!saved && temporary != null) temporary.delete();
            uploading.set(false);lastActivity=clock.getAsLong();
        }
    }
    private Session session(Request request,String peer) {
        String cookie=request.headers.getOrDefault("cookie","");
        for(String part:cookie.split(";")) {
            String value=part.trim();
            if(value.startsWith("cc3media=")) synchronized(sessions) {
                Session session=sessions.get(value.substring(9));
                return session!=null && session.peer.equals(peer)?session:null;
            }
        }
        return null;
    }
    private Request request(InputStream in) throws IOException {
        Request result=new Request();String line=line(in,8192);String[] words=line.split(" ",-1);
        if(words.length!=3 || !words[2].equals("HTTP/1.1") || !words[1].startsWith("/")
                || words[1].contains("#") || !words[0].matches("GET|POST")) throw new Problem(400,"Invalid request");
        result.method=words[0];result.path=words[1];int total=line.length();
        while(true) {
            line=line(in,8192);total+=line.length()+2;
            if(total>16384 || result.headers.size()>48) throw new Problem(431,"Request headers are too large");
            if(line.isEmpty())break;int colon=line.indexOf(':');
            if(colon<=0 || !line.substring(0,colon).matches("[A-Za-z0-9-]+"))throw new Problem(400,"Invalid request header");
            String name=line.substring(0,colon).toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();
            if(result.headers.put(name,value)!=null)throw new Problem(400,"Duplicate request header");
        }
        if(result.headers.containsKey("transfer-encoding"))throw new Problem(400,"Upload requires a known file length");
        String length=result.headers.get("content-length");
        if(length!=null) {
            if(!length.matches("[0-9]{1,19}"))throw new Problem(400,"Invalid file length");
            try{result.length=Long.parseLong(length);}catch(NumberFormatException invalid){throw new Problem(400,"Invalid file length");}
        } else if(result.method.equals("POST"))throw new Problem(411,"Upload requires a known file length");
        if(result.headers.containsKey("expect") && !result.headers.get("expect").equalsIgnoreCase("100-continue"))
            throw new Problem(417,"Unsupported request expectation");
        return result;
    }
    private static String line(InputStream input,int max) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int previous=-1;
        while(bytes.size()<=max) {
            int value=input.read();if(value<0)throw new EOFException("Incomplete request");
            if(value==10) {
                if(previous!=13)throw new Problem(400,"Invalid request line ending");
                byte[] raw=bytes.toByteArray();return new String(raw,0,raw.length-1,StandardCharsets.US_ASCII);
            }
            if(value!=13 && (value<32 || value>126))throw new Problem(400,"Invalid request header character");
            if(previous==13)throw new Problem(400,"Invalid request line ending");
            bytes.write(value);previous=value;
        }
        throw new Problem(431,"Request line is too long");
    }
    private static byte[] read(InputStream in,int length) throws IOException {
        byte[] value=new byte[length];int done=0;
        while(done<length){int count=in.read(value,done,length-done);if(count<0)throw new EOFException("Incomplete request body");done+=count;}
        return value;
    }
    private static String decode(String encoded) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<encoded.length();i++) {
            char c=encoded.charAt(i);
            if(c=='%') {
                if(i+2>=encoded.length())throw new IOException("Invalid file name encoding");
                int a=Character.digit(encoded.charAt(++i),16),b=Character.digit(encoded.charAt(++i),16);
                if(a<0 || b<0)throw new IOException("Invalid file name encoding");out.write((a<<4)|b);
            } else if(c<=127)out.write(c=='+'?' ':c);else throw new IOException("Encode file names as UTF-8");
        }
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}
        catch(CharacterCodingException invalid){throw new IOException("Invalid UTF-8 file name");}
    }
    private String token() {byte[] value=new byte[32];random.nextBytes(value);StringBuilder text=new StringBuilder();for(byte b:value)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    public static String quote(String input) {
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<input.length();i++){char c=input.charAt(i);if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}
        return out.append('"').toString();
    }
    private static void json(OutputStream out,int code,String json,Map<String,String> extra) throws IOException {
        response(out,code,"application/json; charset=utf-8",json.getBytes(StandardCharsets.UTF_8),extra);
    }
    private static void response(OutputStream out,int code,String type,byte[] body,Map<String,String> extra) throws IOException {
        String reason=code==200?"OK":code==201?"Created":"Request not accepted";
        StringBuilder header=new StringBuilder("HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length
                +"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY"
                +"\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'\r\n");
        for(Map.Entry<String,String> value:extra.entrySet())header.append(value.getKey()).append(": ").append(value.getValue()).append("\r\n");
        out.write(header.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    @Override public synchronized void close() {
        running=false;
        if(server!=null)try{server.close();}catch(IOException ignored){}
        synchronized(sockets){for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}sockets.clear();}
        workers.shutdownNow();pairedWorkers.shutdownNow();deadlines.shutdownNow();synchronized(sessions){sessions.clear();}
        if(notified.compareAndSet(false,true))listener.stopped();
    }
}
