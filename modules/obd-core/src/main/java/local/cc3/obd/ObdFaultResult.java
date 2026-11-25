package local.cc3.obd;

import java.util.LinkedHashSet;
import java.util.Set;

/** One standard code category, scoped to the engine ECU selected during discovery. */
public final class ObdFaultResult {
    public enum State {
        NOT_READ,
        READING,
        READ,
        NO_RESPONSE,
        NOT_SUPPORTED,
        DECLINED,
        INCOMPLETE,
        ERROR,
        OTHER_ECU,
        INTERRUPTED
    }
    public final int service, count;
    public final String category, detail;
    public final State state;

    private ObdFaultResult(int service, State state, int count, String detail) {
        this.service = service;
        this.state = state;
        this.count = count;
        this.detail = detail;
        category = service == 3 ? "Stored" : service == 7 ? "Pending" : service == 10 ? "Permanent" : "";
        if (category.isEmpty())
            throw new IllegalArgumentException("Not a standard code category");
    }

    public static ObdFaultResult waiting(int service) {
        return new ObdFaultResult(service, State.NOT_READ, 0, "No result has been requested.");
    }
    static ObdFaultResult reading(int service) {
        return new ObdFaultResult(service, State.READING, 0, "Waiting for the engine ECU.");
    }
    ObdFaultResult interrupted() {
        return state == State.READING ? new ObdFaultResult(service, State.INTERRUPTED, 0,
                                                "The scan stopped before this result arrived.")
                                      : this;
    }

    static ObdFaultResult decode(ElmProtocol.Reply reply, String ecu, int service) {
        Set<String> codes = new LinkedHashSet<>();
        boolean selected = false, malformed = false, other = false;
        for (ElmProtocol.Message message : reply.messages) {
            if ((message.data[0] & 255) != service + 0x40)
                continue;
            java.util.List<String> decoded = ElmProtocol.troubleCodes(message, service);
            if (message.source.equals(ecu)) {
                if (decoded == null)
                    malformed = true;
                else {
                    selected = true;
                    codes.addAll(decoded);
                }
            } else if (decoded != null)
                other = true;
        }
        if (reply.responseError != null) {
            if ("NO DATA".equals(reply.responseError))
                return new ObdFaultResult(service, State.NO_RESPONSE, 0,
                        "The adapter received no matching reply. This does not mean zero codes.");
            return new ObdFaultResult(
                    service, State.ERROR, 0, reply.responseError + ". Retry the code scan.");
        }
        int rejection = reply.rejection(ecu, service);
        if (malformed || rejection == 0x100)
            return new ObdFaultResult(
                    service, State.INCOMPLETE, 0, "Engine ECU " + ecu + " sent an incomplete code response.");
        // A pending notification followed by a valid final response is not a failed read.
        if (selected && rejection >= 0 && rejection != 0x78)
            return new ObdFaultResult(service, State.ERROR, 0,
                    "Conflicting responses from engine ECU " + ecu + ". Retry the scan.");
        if (selected)
            return new ObdFaultResult(service, State.READ, codes.size(),
                    "Engine ECU " + ecu
                            + (codes.isEmpty() ? " reported no codes in this category."
                                               : " returned the codes shown below."));
        if (rejection == 0x11)
            return new ObdFaultResult(service, State.NOT_SUPPORTED, 0,
                    "Engine ECU " + ecu + " explicitly does not support this code service.");
        if (rejection == 0x78)
            return new ObdFaultResult(service, State.INCOMPLETE, 0,
                    "The ECU requested more time; no final code result arrived.");
        if (rejection >= 0)
            return new ObdFaultResult(service, State.DECLINED, 0,
                    String.format(java.util.Locale.ROOT, "Engine ECU %s declined this request (%02X).", ecu,
                            rejection));
        if (other)
            return new ObdFaultResult(service, State.OTHER_ECU, 0,
                    "Another ECU replied; engine ECU " + ecu + " has no complete result.");
        return new ObdFaultResult(service, State.NO_RESPONSE, 0,
                "No complete code reply from engine ECU " + ecu + ". Retry the scan.");
    }

    public String label() {
        switch (state) {
            case READING:
                return "Reading…";
            case READ:
                return count == 0 ? "No codes reported"
                                  : count + (count == 1 ? " code reported" : " codes reported");
            case NO_RESPONSE:
                return "No response";
            case NOT_SUPPORTED:
                return "Not supported";
            case DECLINED:
                return "Request declined";
            case INCOMPLETE:
                return "Incomplete response";
            case ERROR:
                return "Read failed";
            case OTHER_ECU:
                return "Other ECU replied";
            case INTERRUPTED:
                return "Scan interrupted";
            default:
                return "Not read";
        }
    }
    String summary() {
        return category + ": " + (state == State.READ ? "read" : label().toLowerCase(java.util.Locale.ROOT));
    }
}
