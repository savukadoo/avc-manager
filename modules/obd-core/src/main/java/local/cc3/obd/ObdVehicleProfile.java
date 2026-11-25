package local.cc3.obd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable, compiled read-only vehicle definition, independent of Android and adapter brand. */
public final class ObdVehicleProfile {
    public final String id, label, description, sources, primaryEcu, protocolPattern, functionalHeader;
    public final List<ObdPid> sensors;
    public final List<ObdCategory> categories;
    public final List<String> directProbeIds;

    public ObdVehicleProfile(String id, String label, String description, String sources,
            List<ObdPid> sensors, List<ObdCategory> categories, List<String> directProbeIds,
            String primaryEcu, String protocolPattern, String functionalHeader) {
        if (id == null || !id.matches("[a-z0-9_.-]{1,80}"))
            throw new IllegalArgumentException("Profile ID");
        this.id = id;
        this.label = label;
        this.description = description;
        this.sources = sources;
        this.primaryEcu = primaryEcu;
        this.protocolPattern = protocolPattern;
        this.functionalHeader = functionalHeader;
        this.sensors = Collections.unmodifiableList(new ArrayList<>(sensors));
        this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        this.directProbeIds = Collections.unmodifiableList(new ArrayList<>(directProbeIds));
        if (sensors.isEmpty())
            throw new IllegalArgumentException("A profile needs at least one sensor");
        Set<String> ids = new HashSet<>();
        for (ObdPid sensor : sensors) {
            if (!ids.add(sensor.id))
                throw new IllegalArgumentException("Duplicate sensor ID: " + sensor.id);
            if (!sensor.header.isEmpty() && functionalHeader.isEmpty())
                throw new IllegalArgumentException("Functional header required");
        }
        for (String probe : directProbeIds)
            if (!ids.contains(probe))
                throw new IllegalArgumentException("Unknown probe: " + probe);
        Set<String> groups = new HashSet<>();
        for (ObdCategory category : categories) {
            if (!groups.add(category.id) || sensor(category.primaryId) == null
                    || !sensor(category.primaryId).category.equals(category.id))
                throw new IllegalArgumentException("Invalid category");
        }
        for (ObdPid sensor : sensors)
            if (!sensor.category.isEmpty() && !groups.contains(sensor.category))
                throw new IllegalArgumentException("Sensor category not registered: " + sensor.category);
        if (!protocolPattern.isEmpty())
            java.util.regex.Pattern.compile(protocolPattern);
        if (!functionalHeader.isEmpty() && !ObdPid.validHeader(functionalHeader))
            throw new IllegalArgumentException("Functional header");
    }

    public ObdPid sensor(String id) {
        for (ObdPid sensor : sensors)
            if (sensor.id.equals(id))
                return sensor;
        return null;
    }
    public ObdCategory category(String id) {
        for (ObdCategory category : categories)
            if (category.id.equals(id))
                return category;
        return null;
    }
    public boolean enhancedApplicable(String ecu, String protocol) {
        return (primaryEcu.isEmpty() || primaryEcu.equals(ecu))
                && (protocolPattern.isEmpty()
                        || protocol.toUpperCase(Locale.ROOT).replace(" ", "").matches(protocolPattern));
    }
}
