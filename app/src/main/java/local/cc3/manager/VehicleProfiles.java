package local.cc3.manager;

import android.content.SharedPreferences;
import local.cc3.obd.ObdEngine;
import local.cc3.obd.ObdProfiles;
import local.cc3.obd.ObdVehicleProfile;

/** Persistence/migration boundary; no adapter or dashboard preference is erased by a profile change. */
final class VehicleProfiles {
    private static final String PROFILE_ID = "obd_vehicle_profile";
    private static final String MODEL = "obd_vehicle_model";

    static ObdVehicleProfile current(SharedPreferences prefs) {
        String saved = prefs.getString(PROFILE_ID, null);
        Boolean legacy = prefs.contains("obd_toyota_1gd")
                ? prefs.getBoolean("obd_toyota_1gd", false) : null;
        return ObdProfiles.byId(ObdProfiles.migratedId(saved, legacy, prefs.contains("obd_adapter_address")));
    }

    static void restore(SharedPreferences prefs, ObdEngine engine) {
        ObdVehicleProfile profile = current(prefs);
        engine.setProfile(profile);
        save(prefs, profile, model(prefs, profile));
    }

    static String model(SharedPreferences prefs, ObdVehicleProfile profile) {
        return profile.id.equals(prefs.getString(PROFILE_ID, null))
                ? profile.modelName(prefs.getString(MODEL, "")) : "";
    }

    static String label(SharedPreferences prefs, ObdVehicleProfile profile, String separator) {
        String model = model(prefs, profile);
        return model.isEmpty() ? profile.label : profile.manufacturer + " " + model + separator + profile.engine;
    }

    static void select(SharedPreferences prefs, ObdEngine engine, ObdVehicleProfile profile, String model) {
        String selected = profile.modelName(model);
        engine.setProfile(profile, !selected.equals(model(prefs, profile)));
        save(prefs, profile, selected);
    }

    private static void save(SharedPreferences prefs, ObdVehicleProfile profile, String model) {
        SharedPreferences.Editor edit = prefs.edit().putString(PROFILE_ID, profile.id);
        if (model.isEmpty())
            edit.remove(MODEL);
        else
            edit.putString(MODEL, model);
        edit.apply();
    }

    private VehicleProfiles() {}
}
