package com.o3.server;

public class ObservationRecord {

    // lightweight record model kept for simple data passing.
    private String targetBodyName;
    private String centerBodyName;
    private String epoch;

    public ObservationRecord(String target, String center, String epoch) {
        // assign direct values; no heavy logic here.
        this.targetBodyName = target;
        this.centerBodyName = center;
        this.epoch = epoch;
    }

    // expose minimal getters used by any caller/tests.
    public String getTargetBodyName() { return targetBodyName; }
    public String getCenterBodyName() { return centerBodyName; }
    public String getEpoch() { return epoch; }
}
