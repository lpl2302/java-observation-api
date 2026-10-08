package com.o3.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class ObservationMapper {

    // format used in api response metadata timestamps.
    private static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public JSONObject toResponseObject(MessageDatabase.DbMessageRow row, String ownerNickname) {
        // message_json in db stores original observation object.
        JSONObject stored = new JSONObject(row.messageJson);

        // copy core observation fields.
        JSONObject out = new JSONObject();
        out.put("target_body_name", stored.getString("target_body_name"));
        out.put("center_body_name", stored.getString("center_body_name"));
        out.put("epoch", stored.getString("epoch"));

        // include only representations that were stored.
        if (stored.has("orbital_elements")) out.put("orbital_elements", stored.getJSONObject("orbital_elements"));
        if (stored.has("state_vector")) out.put("state_vector", stored.getJSONObject("state_vector"));

        JSONObject storedMeta = stored.optJSONObject("metadata");

        // construct server-side metadata for responses.
        JSONObject meta = new JSONObject();
        meta.put("record_time_received", UTC_MILLIS.format(Instant.ofEpochMilli(row.recordTimeReceived)));
        meta.put("record_owner", ownerNickname);
        meta.put("id", row.id);
        meta.put("record_payload", row.recordPayload);

        // observatory is optional and preserved if present.
        if (storedMeta != null && storedMeta.has("observatory")) {
            JSONArray obs = storedMeta.optJSONArray("observatory");
            if (obs != null) meta.put("observatory", obs);
        }

        // update fields are optional and added only for edited records.
        if (storedMeta != null) {
            if (storedMeta.has("update_reason")) {
                meta.put("update_reason", storedMeta.optString("update_reason", "N/A"));
            }
            if (storedMeta.has("edited")) {
                meta.put("edited", storedMeta.getString("edited"));
            }
        }

        out.put("metadata", meta);
        return out;
    }
}
