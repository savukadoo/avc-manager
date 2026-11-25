package local.cc3.obd;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Read-only ELM command boundary, prompt framing and bounded response decoding. */
public final class ElmProtocol {
    static final int MAX_RESPONSE = 16384, MAX_PAYLOAD = 4095, MAX_LINES = 256;
    private final ObdLink link;
    private final BooleanSupplier cancelled;
    private final ObdCommandPolicy policy;

    ElmProtocol(ObdLink link, BooleanSupplier cancelled) {
        this(link, cancelled, ObdProfiles.standard());
    }
    ElmProtocol(ObdLink link, BooleanSupplier cancelled, ObdVehicleProfile profile) {
        this.link = link;
        this.cancelled = cancelled;
        this.policy = new ObdCommandPolicy(profile);
    }
    public static String allowedCommand(String input) {
        return allowedCommand(input, ObdProfiles.standard());
    }
    public static String allowedCommand(String input, ObdVehicleProfile profile) {
        return new ObdCommandPolicy(profile).allow(input);
    }

    // Synchronization also prevents overlapping queries if a future caller uses this directly.
    synchronized Reply query(String request, long timeoutMillis) throws IOException {
        String command = policy.allow(request);
        if (timeoutMillis <= 0 || timeoutMillis > 15000)
            throw new IllegalArgumentException("Invalid deadline");
        checkCancelled();
        link.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        while (true) {
            checkCancelled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0)
                throw new SocketTimeoutException("Adapter response timed out; reconnect to resynchronise");
            int count = link.read(chunk, 0, chunk.length, Math.min(250, Math.max(1, remaining / 1000000L)));
            checkCancelled();
            if (count < 0)
                throw new EOFException("Adapter disconnected");
            if (count > chunk.length)
                throw new IOException("Invalid transport read length");
            for (int i = 0; i < count; i++) {
                int value = chunk[i] & 255;
                if (value == '>') {
                    // An idle prompt may precede the first reset response. Never accept it as data.
                    if (buffer.toString("US-ASCII").trim().isEmpty())
                        continue;
                    for (int j = i + 1; j < count; j++) {
                        int tail = chunk[j] & 255;
                        if (tail != 13 && tail != 10 && tail != 32 && tail != 9)
                            throw new IOException("Unexpected data after adapter prompt; reconnect");
                    }
                    return parse(buffer.toString("US-ASCII"), command);
                }
                if (value != 13 && value != 10 && value != 9 && (value < 32 || value > 126))
                    throw new IOException("Non-text adapter response");
                if (buffer.size() >= MAX_RESPONSE)
                    throw new IOException("Adapter response exceeds size limit");
                buffer.write(value);
            }
        }
    }

    private void checkCancelled() throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("Connection cancelled");
    }

    static final class Message {
        final String source;
        final byte[] data;
        final boolean can;
        Message(String source, byte[] data, boolean can) {
            this.source = source;
            this.data = data.clone();
            this.can = can;
        }
    }

    static final class Reply {
        final List<String> lines;
        final List<Message> messages;
        final String error;
        /** Adapter/framing errors apply to the whole reply; ECU refusals keep their source. */
        final String responseError;
        Reply(List<String> lines, List<Message> messages, String error, String responseError) {
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
            this.error = error;
            this.responseError = responseError;
        }
        int rejection(String source, int service) {
            int result = -1;
            for (Message message : messages)
                if (message.source.equals(source) && message.data.length >= 2
                        && (message.data[0] & 255) == 0x7F && (message.data[1] & 255) == service) {
                    if (message.data.length != 3)
                        return 0x100;
                    int code = message.data[2] & 255;
                    if (code != 0x78)
                        return code;
                    result = code;
                }
            return result;
        }
        String text() {
            return String.join(" · ", lines);
        }
        Message pid(String source, int pid) {
            return sensor(source, 1, pid);
        }
        Message sensor(String source, int service, int pid) {
            int prefix = service == 0x22 ? 3 : 2;
            for (Message message : messages)
                if (message.source.equals(source) && message.data.length >= prefix
                        && (message.data[0] & 255) == service + 0x40
                        && (service == 0x22 ? (message.data[1] & 255) * 256 + (message.data[2] & 255)
                                            : message.data[1] & 255)
                                == pid)
                    return message;
            return null;
        }
    }

    private static final class Assembly {
        final int length;
        int sequence = 1;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        Assembly(int length) {
            this.length = length;
        }
        void append(byte[] bytes, int offset) {
            int count = Math.min(length - data.size(), bytes.length - offset);
            if (count > 0)
                data.write(bytes, offset, count);
        }
    }

    static Reply parse(String raw, String request) throws IOException {
        String command = ObdCommandPolicy.normalize(request);
        if (raw.length() > MAX_RESPONSE)
            throw new IOException("Adapter response exceeds size limit");
        String[] incoming = raw.toUpperCase(Locale.ROOT).split("[\r\n]+");
        if (incoming.length > MAX_LINES)
            throw new IOException("Too many response lines");
        List<String> lines = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        Map<String, Assembly> assemblies = new LinkedHashMap<>();
        String error = null;
        int expected = command.startsWith("AT") ? -1 : Integer.parseInt(command.substring(0, 2), 16) + 0x40;
        Assembly numbered = null;
        int numberedIndex = 0;
        for (String input : incoming) {
            String line = input.trim().replace(">", "");
            if (line.isEmpty() || line.replace(" ", "").equals(command))
                continue;
            line = line.replaceFirst("^SEARCHING\\.{0,3}\\s*", "").trim();
            if (line.isEmpty() || line.startsWith("BUS INIT:") && line.endsWith("OK"))
                continue;
            lines.add(line);
            String failure = errorLine(line);
            if (failure != null) {
                error = failure;
                continue;
            }
            if (expected < 0 || line.equals("OK"))
                continue;
            if (line.matches("[0-9A-F]{3}")) {
                int length = Integer.parseInt(line, 16);
                numbered = length > 0 && length <= MAX_PAYLOAD ? new Assembly(length) : null;
                numberedIndex = 0;
                continue;
            }
            if (line.matches("[0-9A-F]:.*")) {
                int index = Integer.parseInt(line.substring(0, 1), 16);
                byte[] part = hexBytes(line.substring(2).replace(" ", ""));
                if (numbered == null || index != (numberedIndex & 15) || part == null) {
                    numbered = null;
                    error = "Incomplete numbered response";
                    continue;
                }
                numberedIndex++;
                numbered.append(part, 0);
                if (numbered.data.size() == numbered.length) {
                    addMessage(messages, "ECU", numbered.data.toByteArray(), true, expected);
                    numbered = null;
                }
                continue;
            }
            String source = "ECU";
            boolean can = false;
            String compact = line.replaceAll("\\s+", "");
            String[] tokens = line.split("\\s+");
            if (tokens[0].matches("[0-7][0-9A-F]{2}|[01][0-9A-F]{7}") && tokens.length > 1) {
                source = tokens[0];
                can = true;
                int start = tokens[0].length();
                compact = compact.substring(start);
                if (tokens.length > 2 && tokens[1].length() == 1)
                    compact = compact.substring(1); // Optional DLC.
            } else if (tokens.length >= 5 && tokens[0].equals("18")
                    && (tokens[1].equals("DA") || tokens[1].equals("DB"))) {
                source = tokens[0] + tokens[1] + tokens[2] + tokens[3];
                compact = compact.substring(8);
                can = true;
            } else if (compact.matches("[0-7][0-9A-F]+") && (compact.length() & 1) == 1
                    && compact.length() >= 7) {
                source = compact.substring(0, 3);
                compact = compact.substring(3);
                can = true;
            } else if (compact.matches("18D[AB][0-9A-F]{4}.*") && compact.length() >= 12) {
                source = compact.substring(0, 8);
                compact = compact.substring(8);
                can = true;
            }
            byte[] bytes = hexBytes(compact);
            if (bytes == null || bytes.length == 0)
                continue;
            if (!can && bytes.length >= 5 && (bytes[0] & 255) != expected && (bytes[0] & 255) != 0x7F
                    && ((bytes[3] & 255) == expected || (bytes[3] & 255) == 0x7F)) {
                source = String.format(
                        Locale.ROOT, "%02X%02X%02X", bytes[0] & 255, bytes[1] & 255, bytes[2] & 255);
                bytes = Arrays.copyOfRange(bytes, 3, bytes.length - 1); // Legacy header and checksum.
            }
            if (bytes.length == 0)
                continue;
            int first = bytes[0] & 255;
            if (!can && (first == expected || first == 0x7F)) {
                addMessage(messages, source, bytes, false, expected);
                continue;
            }
            int type = first >> 4;
            if (type == 0) {
                int length = first & 15;
                if (length > 0 && length <= 7 && bytes.length >= length + 1)
                    addMessage(messages, source, Arrays.copyOfRange(bytes, 1, length + 1), true, expected);
                else
                    error = "Incomplete CAN response";
            } else if (type == 1 && bytes.length >= 3) {
                int length = ((first & 15) << 8) | (bytes[1] & 255);
                if (length < 8 || length > MAX_PAYLOAD
                        || assemblies.size() >= 16 && !assemblies.containsKey(source)) {
                    error = "Invalid ISO-TP length";
                    continue;
                }
                Assembly assembly = new Assembly(length);
                assembly.append(bytes, 2);
                assemblies.put(source, assembly);
            } else if (type == 2) {
                Assembly assembly = assemblies.get(source);
                if (assembly == null || (first & 15) != (assembly.sequence & 15)) {
                    assemblies.remove(source);
                    error = "Out-of-order ISO-TP response";
                    continue;
                }
                assembly.sequence++;
                assembly.append(bytes, 1);
                if (assembly.data.size() == assembly.length) {
                    addMessage(messages, source, assembly.data.toByteArray(), true, expected);
                    assemblies.remove(source);
                }
            } else if (first == expected || first == 0x7F) {
                addMessage(messages, source, bytes, can, expected);
            }
        }
        if (!assemblies.isEmpty() || numbered != null)
            error = "Incomplete ISO-TP response";
        String responseError = error;
        for (Message message : messages)
            if ((message.data[0] & 255) == 0x7F)
                error = "ECU declined read request";
        return new Reply(lines, messages, error, responseError);
    }

    private static void addMessage(List<Message> messages, String source, byte[] data, boolean can,
            int expected) throws IOException {
        if (data.length == 0 || (data[0] & 255) != expected && (data[0] & 255) != 0x7F)
            return;
        if (messages.size() >= 256)
            throw new IOException("Too many ECU messages");
        messages.add(new Message(source, data, can));
    }

    private static byte[] hexBytes(String value) {
        if (value.isEmpty() || (value.length() & 1) != 0 || !value.matches("[0-9A-F]+"))
            return null;
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static String errorLine(String line) {
        if (line.equals("?"))
            return "Adapter does not support this read command";
        for (String error : new String[] {"NO DATA", "UNABLE TO CONNECT", "BUS ERROR", "CAN ERROR", "STOPPED",
                     "BUFFER FULL", "DATA ERROR", "RX ERROR", "BUS INIT: ERROR", "LV RESET"})
            if (line.contains(error))
                return error;
        return null;
    }

    static Map<String, Set<Integer>> supported(Reply reply, int base) {
        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        for (Message message : reply.messages) {
            byte[] data = message.data;
            if (data.length < 6 || (data[0] & 255) != 0x41 || (data[1] & 255) != base)
                continue;
            Set<Integer> pids = result.get(message.source);
            if (pids == null) {
                pids = new LinkedHashSet<>();
                result.put(message.source, pids);
            }
            for (int bit = 0; bit < 32; bit++)
                if (((data[2 + bit / 8] & 255) & (0x80 >> (bit % 8))) != 0)
                    pids.add(base + bit + 1);
        }
        return result;
    }

    static List<String> troubleCodes(Message message, int service) {
        byte[] data = message.data;
        if (data.length == 0 || (data[0] & 255) != service + 0x40)
            return Collections.emptyList();
        int offset = 1, count = (data.length - 1) / 2;
        boolean counted = message.can
                || message.source.equals("ECU") && data.length >= 2 && ((data.length - 1) & 1) != 0
                        && 2 + (data[1] & 255) * 2 == data.length;
        if (counted) {
            if (data.length < 2)
                return null;
            count = data[1] & 255;
            offset = 2;
            if (count > 127 || offset + count * 2 > data.length)
                return null;
        } else if (data.length < 3 || ((data.length - 1) & 1) != 0) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int a = data[offset + i * 2] & 255, b = data[offset + i * 2 + 1] & 255;
            if (a == 0 && b == 0)
                continue;
            codes.add(String.format(
                    Locale.ROOT, "%c%01X%01X%02X", "PCBU".charAt(a >> 6), (a >> 4) & 3, a & 15, b));
        }
        return codes;
    }
}
