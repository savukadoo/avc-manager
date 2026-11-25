package local.cc3.manager;

import android.content.SharedPreferences;
import local.cc3.obd.ObdEngine;
import local.cc3.obd.ObdProfiles;
import local.cc3.obd.ObdVehicleProfile;

/** Persistence/migration boundary; no adapter or dashboard preference is erased by a profile change. */
final class VehicleProfiles {
    static ObdVehicleProfile current(SharedPreferences prefs){
        String saved=prefs.getString("obd_vehicle_profile",null);
        Boolean legacy=prefs.contains("obd_toyota_1gd")?prefs.getBoolean("obd_toyota_1gd",false):null;
        return ObdProfiles.byId(ObdProfiles.migratedId(saved,legacy,prefs.contains("obd_adapter_address")));
    }
    static void restore(SharedPreferences prefs,ObdEngine engine){
        select(prefs, engine, current(prefs));
    }
    static void select(SharedPreferences prefs,ObdEngine engine,ObdVehicleProfile profile){
        engine.setProfile(profile);
        prefs.edit().putString("obd_vehicle_profile",profile.id).apply();
    }
    private VehicleProfiles(){ }
}
