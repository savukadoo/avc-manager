package local.cc3.obd.profiles;
import java.util.Arrays;
import java.util.Collections;
import local.cc3.obd.*;
public final class GenericObdProfile {
    public static ObdVehicleProfile create() {
        return new ObdVehicleProfile("generic-obd2", "Standard OBD-II",
                "For most OBD-II vehicles. Finds the standard measurements reported by your vehicle.",
                "Standard OBD sensor definitions. Availability is discovered per ECU; no vehicle-specific "
                        + "measurements are assumed.",
                StandardSensors.all(),
                Arrays.asList(new ObdCategory("dpf", "DPF", "dpf_pressure",
                        "Available standard DPF and exhaust sensors; only valid responding fields are "
                                + "selectable.")),
                Collections.emptyList(), "", "", "");
    }
    private GenericObdProfile() {}
}
