package local.cc3.obd;
import java.util.*;
import local.cc3.obd.profiles.GenericObdProfile;
import local.cc3.obd.profiles.Toyota1gdProfile;

/** Contributor registration point: add a compiled profile here; engine and Android UI stay unchanged. */
public final class ObdProfiles {
    private static final List<ObdVehicleProfile> ALL = Collections.unmodifiableList(
            Arrays.asList(GenericObdProfile.create(), Toyota1gdProfile.create()));
    private static final ObdVehicleCatalog CATALOG = new ObdVehicleCatalog(ALL);
    private static final List<ObdPid> SENSORS = sensors();
    public static List<ObdVehicleProfile> all() {
        return ALL;
    }
    public static ObdVehicleProfile standard() {
        return ALL.get(0);
    }
    public static ObdVehicleCatalog catalog() {
        return CATALOG;
    }
    public static ObdVehicleProfile byId(String id) {
        for (ObdVehicleProfile p : ALL)
            if (p.id.equals(id))
                return p;
        return standard();
    }
    public static List<ObdPid> allSensors() {
        return SENSORS;
    }
    /** Existing CC3 installs migrate their explicit legacy flag; new installs start generic. */
    public static String migratedId(String savedId, Boolean legacyToyota, boolean existingObdSetup) {
        if (savedId != null) {
            for (ObdVehicleProfile p : ALL)
                if (p.id.equals(savedId))
                    return p.id;
            return standard().id;
        }
        return Boolean.TRUE.equals(legacyToyota) || legacyToyota == null && existingObdSetup
                ? Toyota1gdProfile.ID
                : standard().id;
    }
    private static List<ObdPid> sensors() {
        Map<String, ObdPid> sensors = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (ObdVehicleProfile p : ALL) {
            if (!ids.add(p.id))
                throw new IllegalArgumentException("Duplicate profile ID");
            if (p != standard() && p.models.isEmpty())
                throw new IllegalArgumentException("Vehicle fitment is required for profile: " + p.id);
            for (ObdPid sensor : p.sensors) {
                ObdPid previous = sensors.putIfAbsent(sensor.id, sensor);
                if (previous != null && previous != sensor)
                    throw new IllegalArgumentException("Sensor IDs must be globally unique: " + sensor.id);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(sensors.values()));
    }
    private ObdProfiles() {}
}
