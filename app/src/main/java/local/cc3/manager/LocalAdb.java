package local.cc3.manager;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.math.BigInteger;
import java.util.Locale;

/** Minimal legacy ADB transport for AVC Manager's fixed commands on Android 10. */
final class LocalAdb implements AutoCloseable {
    private static final String PACKAGE = "com.teyes.hanna";
    private static final String KEY = "cc3-manager-local-adb";
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
        0x1a, 0x05, 0x00, 0x04, 0x14
    };
    private static final int LIMIT = 4096;
    private final Socket socket = new Socket();
    private DataInputStream input;
    private int channel;

    interface Approval {
        void requested(String fingerprint);
    }

    LocalAdb(Approval approval) throws Exception {
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", FytAdbController.ADB_PORT), 1500);
            socket.setSoTimeout(4500);
            input = new DataInputStream(socket.getInputStream());
            send(CNXN, 0x01000000, LIMIT, utf8("host::\0"));
            Packet response = receive();
            if (response.type == AUTH) {
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
                keys.load(null);
                if (!keys.containsAlias(KEY)) {
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
                    generator.initialize(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_SIGN)
                            .setKeySize(2048).setDigests(KeyProperties.DIGEST_NONE)
                            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build());
                    generator.generateKeyPair();
                }
                KeyStore.PrivateKeyEntry key = (KeyStore.PrivateKeyEntry) keys.getEntry(KEY, null);
                if (response.arg0 != 1 || response.data.length != 20) {
                    throw new IOException("Unexpected ADB authentication request.");
                }
                Signature signature = Signature.getInstance("NONEwithRSA");
                signature.initSign(key.getPrivateKey());
                signature.update(SHA1_DIGEST_INFO);
                signature.update(response.data);
                send(AUTH, 2, 0, signature.sign());
                response = receive();
                if (response.type == AUTH) {
                    byte[] publicKey = encode((RSAPublicKey) key.getCertificate().getPublicKey());
                    approval.requested(fingerprint(publicKey));
                    send(AUTH, 3, 0, utf8(Base64.encodeToString(publicKey, Base64.NO_WRAP)
                            + " AVC-Manager@localhost\0"));
                    socket.setSoTimeout(25000);
                    try {
                        response = receive();
                    } catch (SocketTimeoutException ignored) {
                        throw new IOException("ADB approval timed out. Try again and accept the Android prompt.");
                    }
                    socket.setSoTimeout(4500);
                }
            }
            if (response.type != CNXN) throw new IOException("The head unit did not authorize ADB.");
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    /** Checks the initial challenge without sending a public key, signature or shell command. */
    static boolean authenticationRequired() throws IOException {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", FytAdbController.ADB_PORT), 1000);
            probe.setSoTimeout(1500);
            byte[] hello=utf8("host::\0");
            ByteBuffer header=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(CNXN).putInt(0x01000000).putInt(LIMIT).putInt(hello.length).putInt(checksum(hello)).putInt(~CNXN);
            probe.getOutputStream().write(header.array());probe.getOutputStream().write(hello);
            byte[] response=new byte[24];new DataInputStream(probe.getInputStream()).readFully(response);
            ByteBuffer packet=ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
            int type=packet.getInt(),argument=packet.getInt();packet.getInt();int length=packet.getInt();int sum=packet.getInt();int magic=packet.getInt();
            if(type!=AUTH || magic!=~AUTH || argument!=1 || length!=20)return false;
            byte[] challenge=new byte[20];new DataInputStream(probe.getInputStream()).readFully(challenge);
            return sum==checksum(challenge);
        }
    }

    boolean enabled() throws IOException {
        String found = shell("pm list packages -d --user 0 " + PACKAGE);
        if (found.contains("package:" + PACKAGE)) return false;
        if (shell("pm list packages -e --user 0 " + PACKAGE).contains("package:" + PACKAGE)) return true;
        throw new IOException("Hanna is unavailable for the main Android user.");
    }

    boolean setEnabled(boolean enable) throws IOException {
        shell("pm " + (enable ? "default-state" : "disable-user") + " --user 0 " + PACKAGE);
        if (!enable) shell("am force-stop --user 0 " + PACKAGE);
        boolean result = enabled();
        if (result != enable) throw new IOException("Android did not apply the requested Hanna setting.");
        return result;
    }

    void setMobileData(boolean enable) throws IOException {
        String result = shell(enable ? "svc data enable" : "svc data disable");
        if (result.contains("Exception") || result.contains("Error")) {
            throw new IOException("Android did not accept the mobile data change.");
        }
    }

    private String shell(String command) throws IOException {
        int id = ++channel;
        send(OPEN, id, 0, utf8("shell:" + command + "\0"));
        Packet ack = receive();
        if (ack.type != OKAY || ack.arg1 != id) throw new IOException("ADB shell request failed.");
        int remote = ack.arg0;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (output.size() < 65536) {
            Packet packet = receive();
            if (packet.arg0 != remote || packet.arg1 != id) throw new IOException("Unexpected ADB channel.");
            if (packet.type == CLSE) {
                send(CLSE, id, remote, EMPTY);
                return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            }
            if (packet.type != WRTE) throw new IOException("Unexpected ADB shell packet.");
            output.write(packet.data);
            send(OKAY, id, remote, EMPTY);
        }
        throw new IOException("ADB shell response is too large.");
    }

    private void send(int type, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(type).putInt(arg0).putInt(arg1).putInt(data.length)
                .putInt(checksum(data)).putInt(~type);
        socket.getOutputStream().write(header.array());
        socket.getOutputStream().write(data);
    }

    private Packet receive() throws IOException {
        byte[] bytes = new byte[24];
        input.readFully(bytes);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int type = header.getInt(), arg0 = header.getInt(), arg1 = header.getInt();
        int length = header.getInt(), sum = header.getInt(), magic = header.getInt();
        if (magic != ~type || length < 0 || length > LIMIT) throw new IOException("Invalid ADB packet.");
        byte[] data = new byte[length];
        input.readFully(data);
        if (sum != checksum(data)) throw new IOException("ADB packet checksum mismatch.");
        return new Packet(type, arg0, arg1, data);
    }

    private static byte[] encode(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger radix = BigInteger.ONE.shiftLeft(32);
        ByteBuffer bytes = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(64).putInt(n.mod(radix).modInverse(radix).negate().intValue());
        putWords(bytes, n);
        putWords(bytes, BigInteger.ONE.shiftLeft(4096).mod(n));
        BigInteger exponent = key.getPublicExponent();
        if (exponent.signum() <= 0 || exponent.bitLength() > 32) {
            throw new IllegalArgumentException("Unsupported ADB public key exponent.");
        }
        bytes.putInt(exponent.intValue());
        return bytes.array();
    }

    private static void putWords(ByteBuffer buffer, BigInteger value) {
        for (int i = 0; i < 64; i++) buffer.putInt(value.shiftRight(i * 32).intValue());
    }

    private static String fingerprint(byte[] key) throws Exception {
        StringBuilder output = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(key)) {
            if (output.length() > 0) output.append(':');
            output.append(String.format(Locale.ROOT, "%02X", b & 255));
        }
        return output.toString();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += b & 255;
        return sum;
    }

    @Override public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Connection teardown is best effort.
        }
    }

    private static final class Packet {
        final int type, arg0, arg1;
        final byte[] data;
        Packet(int type, int arg0, int arg1, byte[] data) {
            this.type = type;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }
}
