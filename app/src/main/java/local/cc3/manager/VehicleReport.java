package local.cc3.manager;
import local.cc3.obd.*;
import java.text.SimpleDateFormat;
import java.util.*;
/** Stateless export boundary, independent of Android Views and profile registry globals. */
final class VehicleReport {
    static String create(ObdEngine.Snapshot current, ObdVehicleProfile profile, boolean imperial, java.util.function.BiFunction<ObdPid,ObdEngine.Reading,String> quality) {
        if (current == null || !current.profileId.equals(profile.id)
                || (!current.faultsAttempted && current.readings.isEmpty() && current.sensorChecks.isEmpty()))
            return null;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String exportedAt = format.format(new Date());
        StringBuilder csv = new StringBuilder(
                "record_type,timestamp_utc,sensor,label,value,unit,quality,ecu_source,code,category\r\n");
        csvRow(csv, "session_summary", exportedAt, "", "Connection", "", "",
                "Profile: " + current.profileId + "; Phase: " + current.phase + "; " + current.status
                        + "; Protocol: " + current.protocol,
                reportSource(current.ecu), "", "");
        for (ObdPid pid : profile.sensors) {
            ObdEngine.Reading value = current.readings.get(pid.id);
            if (value == null)
                continue;
            csvRow(csv, "sensor", hasSample(value) ? format.format(new Date(value.timestampMillis)) : "",
                    pid.id, pid.label, hasSample(value) ? pid.valueText(value.value, imperial) : "",
                    pid.unit(imperial), quality.apply(pid, value), reportSource(value.source), "", "");
        }
        for (ObdEngine.Fault fault : current.faults)
            csvRow(csv, "fault", exportedAt, "", "", "", "", "Reported code", reportSource(fault.source),
                    fault.code, fault.category);
        for (Map.Entry<String, ObdSensorCheck> entry : current.sensorChecks.entrySet()) {
            ObdPid pid = profile.sensor(entry.getKey());
            if(pid==null)continue;
            ObdSensorCheck check = entry.getValue();
            csvRow(csv, "sensor_check", exportedAt, pid.id, pid.label, "", pid.unit(imperial),
                    check.label() + ". " + check.detail + " Definition: " + pid.definitionNote()
                            + (check.raw.isEmpty() ? "" : " Raw reply: " + check.raw),
                    reportSource(check.source), "", pid.originLabel);
        }
        for (ObdFaultResult result : current.faultResults)
            csvRow(csv, "fault_category", exportedAt, "", result.category, "", "",
                    result.label() + ". " + result.detail, reportSource(current.ecu), "", result.category);
        csvRow(csv, "fault_summary", exportedAt, "", "", "", "",
                current.faultsLoaded ? current.faults.isEmpty() ? "No codes reported" : "Code scan complete"
                        : current.faultsAttempted ? clean(current.faultStatus)
                                                  : "Codes not read",
                "", "", "");
        return csv.toString();
    }

    static void csvRow(StringBuilder csv, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0)
                csv.append(',');
            String cell=clean(values[i]);
            if(!cell.isEmpty() && "=+-@".indexOf(cell.charAt(0))>=0 && !cell.matches("[-+]?[0-9]+(?:\\.[0-9]+)?"))cell="'"+cell;
            csv.append('"').append(cell.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private static String reportSource(String source) {
        String value = clean(source);
        return value.matches("[0-9A-Fa-f]{2,8}") ? value : "Not reported";
    }


    private static boolean hasSample(ObdEngine.Reading value){return value!=null && value.timestampMillis>0 && Double.isFinite(value.value);}
    private static String clean(String value){return value==null?"":value.replaceAll("[\\p{Cntrl}\\p{Cf}]"," ").trim();}
    private VehicleReport() { }
}
