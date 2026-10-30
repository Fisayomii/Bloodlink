package africa.tesa.bloodlink.dashboard;

/** Contract: GroupCount. Units available of one group and rhesus type, every component counted. */
public record GroupCount(String bloodGroup, String rhesus, int availableUnits) {
}
