package local.cc3.obd;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Index of the vehicle fitments declared by the compiled profiles. */
public final class ObdVehicleCatalog {
    private final Map<String, Map<String, Map<String, ObdVehicleProfile>>> manufacturers =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    ObdVehicleCatalog(List<ObdVehicleProfile> profiles) {
        for (ObdVehicleProfile profile : profiles) {
            for (String model : profile.models) {
                Map<String, Map<String, ObdVehicleProfile>> models = manufacturers.computeIfAbsent(
                        profile.manufacturer, key -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
                Map<String, ObdVehicleProfile> engines = models.computeIfAbsent(model,
                        key -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
                if (engines.putIfAbsent(profile.engine, profile) != null)
                    throw new IllegalArgumentException("Duplicate vehicle engine choice: "
                            + profile.manufacturer + " / " + model + " / " + profile.engine);
            }
        }
    }

    public Set<String> manufacturers() {
        return Collections.unmodifiableSet(manufacturers.keySet());
    }

    public Set<String> models(String manufacturer) {
        return Collections.unmodifiableSet(modelsFor(manufacturer).keySet());
    }

    public Collection<ObdVehicleProfile> engines(String manufacturer, String model) {
        return Collections.unmodifiableCollection(
                modelsFor(manufacturer).getOrDefault(model, Collections.emptyMap()).values());
    }

    private Map<String, Map<String, ObdVehicleProfile>> modelsFor(String manufacturer) {
        return manufacturers.getOrDefault(manufacturer, Collections.emptyMap());
    }
}
