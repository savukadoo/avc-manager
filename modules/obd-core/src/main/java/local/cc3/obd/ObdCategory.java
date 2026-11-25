package local.cc3.obd;

/** Vehicle-owned dashboard grouping; UI code does not need to know its sensor IDs. */
public final class ObdCategory {
    public final String id, label, primaryId, help;
    public ObdCategory(String id, String label, String primaryId, String help) {
        this.id = id;
        this.label = label;
        this.primaryId = primaryId;
        this.help = help;
    }
}
