package local.cc3.obd;
import java.util.*;
/** Exact compiled-profile allowlist. No terminal, actuation, programming or imported expressions. */
public final class ObdCommandPolicy {
    private final Set<String> commands = new LinkedHashSet<>();
    public ObdCommandPolicy(ObdVehicleProfile profile) {
        commands.addAll(Arrays.asList(
                "ATZ", "ATI", "ATE0", "ATL0", "ATS1", "ATH1", "ATSP0", "ATDP", "ATRV", "03", "07", "0A"));
        for (int base = 0; base <= 0xE0; base += 0x20)
            commands.add(String.format(Locale.ROOT, "01%02X", base));
        for (ObdPid sensor : profile.sensors) {
            commands.add(sensor.command());
            if (!sensor.header.isEmpty())
                commands.add("ATSH" + sensor.header);
        }
        if (!profile.functionalHeader.isEmpty())
            commands.add("ATSH" + profile.functionalHeader);
    }
    public String allow(String input) {
        String command = normalize(input);
        if (!commands.contains(command))
            throw new IllegalArgumentException("Command is not part of this vehicle profile");
        return command;
    }
    static String normalize(String input) {
        if (input == null || !input.matches("[a-zA-Z0-9 ]{1,20}"))
            throw new IllegalArgumentException("Only approved read commands are available");
        String command = input.replace(" ", "").toUpperCase(Locale.ROOT);
        if (command.matches("01[0-9A-F]{2}|21[0-9A-F]{2}|22[0-9A-F]{4}|03|07|0A")
                || Arrays.asList("ATZ", "ATI", "ATE0", "ATL0", "ATS1", "ATH1", "ATSP0", "ATDP", "ATRV")
                           .contains(command)
                || command.matches("ATSH([0-9A-F]{3}|[0-9A-F]{6}|[0-9A-F]{8})"))
            return command;
        throw new IllegalArgumentException("Only approved sensor reads are available");
    }
}
