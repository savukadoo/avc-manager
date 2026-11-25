package local.cc3.obd;

/** One immutable discovery/read result. A reply is not proof of a Toyota formula's accuracy. */
public final class ObdSensorCheck {
    public enum State {
        ADVERTISED,
        RESPONDING,
        NOT_ADVERTISED,
        NO_RESPONSE,
        DECLINED,
        INCOMPLETE,
        UNAVAILABLE,
        DISABLED,
        NOT_APPLICABLE
    }
    public final State state;
    public final String detail, source, raw;
    ObdSensorCheck(State state, String detail, String source, String raw) {
        this.state = state;
        this.detail = detail;
        this.source = source;
        this.raw = raw;
    }
    public String label() {
        switch (state) {
            case ADVERTISED:
                return "Reported by ECU";
            case RESPONDING:
                return "Reply received";
            case NOT_ADVERTISED:
                return "Not advertised";
            case NO_RESPONSE:
                return "No response";
            case DECLINED:
                return "Request declined";
            case INCOMPLETE:
                return "Incomplete reply";
            case UNAVAILABLE:
                return "Value unavailable";
            case DISABLED:
                return "Toyota profile off";
            default:
                return "Profile not applicable";
        }
    }
}
