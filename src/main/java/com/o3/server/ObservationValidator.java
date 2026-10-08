package com.o3.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public final class ObservationValidator {

    // static utility class only.
    private ObservationValidator() {}

    private static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    // strict request-time format
    private static final Pattern STRICT_UTC_MILLIS =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");

    public static String nowUtcMillisIso() {
        return UTC_MILLIS.format(Instant.ofEpochMilli(System.currentTimeMillis()));
    }

    public static void validateObservationForCreateOrUpdate(JSONObject json, boolean isUpdate) {
        // required top-level text fields.
        requireString(json, "target_body_name");
        requireString(json, "center_body_name");
        requireIsoInstant(json, "epoch");

        // full observation can contain one or both orbit formats.
        boolean hasOrbital = json.has("orbital_elements");
        boolean hasState = json.has("state_vector");

        if (!hasOrbital && !hasState) {
            throw new IllegalArgumentException("Observation must include orbital_elements and/or state_vector");
        }

        if (hasOrbital) {
            // validate required orbital element numeric fields.
            JSONObject orb = json.getJSONObject("orbital_elements");
            requireNumber(orb, "semi_major_axis_au");
            requireNumber(orb, "eccentricity");
            requireNumber(orb, "inclination_deg");
            requireNumber(orb, "longitude_ascending_node_deg");
            requireNumber(orb, "argument_of_periapsis_deg");
            requireNumber(orb, "mean_anomaly_deg");
        }

        if (hasState) {
            // validate state vector arrays and numeric entries.
            JSONObject sv = json.getJSONObject("state_vector");
            JSONArray pos = sv.getJSONArray("position_au");
            JSONArray vel = sv.getJSONArray("velocity_au_per_day");

            if (pos.length() != 3 || vel.length() != 3) {
                throw new IllegalArgumentException("State vector arrays must have length 3");
            }
            for (int i = 0; i < 3; i++) {
                pos.getDouble(i);
                vel.getDouble(i);
            }
        }

        JSONObject meta = json.getJSONObject("metadata");
        requireString(meta, "record_payload");

        if (meta.has("observatory")) {
            Object obsAny = meta.get("observatory");
            if (!(obsAny instanceof JSONArray obsArr)) {
                throw new IllegalArgumentException("observatory must be an array");
            }
            if (obsArr.length() == 0) {
                throw new IllegalArgumentException("observatory array cannot be empty");
            }

            for (int i = 0; i < obsArr.length(); i++) {
                Object item = obsArr.get(i);
                if (!(item instanceof JSONObject obs)) {
                    throw new IllegalArgumentException("observatory items must be objects");
                }

                obs.getDouble("latitude");
                obs.getDouble("longitude");

                if (obs.has("observatory_name")) {
                    String name = obs.optString("observatory_name", "");
                    if (name.isBlank()) throw new IllegalArgumentException("observatory_name cannot be blank");
                }
                // "weather" may exist and can be any type (feature 5 triggers on its presence)
            }
        }
    }

    public static void validateObservationForPartial(JSONObject json) {
        requireString(json, "target_body_name");
        requireString(json, "center_body_name");
        requireIsoInstant(json, "epoch");

        // partial observation must contain exactly one orbit format.
        boolean hasOrbital = json.has("orbital_elements");
        boolean hasState = json.has("state_vector");

        if (hasOrbital == hasState) {
            throw new IllegalArgumentException("Partial observation must include exactly one of orbital_elements or state_vector");
        }

        if (hasOrbital) {
            // validate orbital fields.
            JSONObject orb = json.getJSONObject("orbital_elements");
            requireNumber(orb, "semi_major_axis_au");
            requireNumber(orb, "eccentricity");
            requireNumber(orb, "inclination_deg");
            requireNumber(orb, "longitude_ascending_node_deg");
            requireNumber(orb, "argument_of_periapsis_deg");
            requireNumber(orb, "mean_anomaly_deg");
        }

        if (hasState) {
            // validate state vector fields.
            JSONObject sv = json.getJSONObject("state_vector");
            JSONArray pos = sv.getJSONArray("position_au");
            JSONArray vel = sv.getJSONArray("velocity_au_per_day");

            if (pos.length() != 3 || vel.length() != 3) {
                throw new IllegalArgumentException("State vector arrays must have length 3");
            }
            for (int i = 0; i < 3; i++) {
                pos.getDouble(i);
                vel.getDouble(i);
            }
        }

        JSONObject meta = json.getJSONObject("metadata");
        requireString(meta, "record_payload");

        // observatory validation mirrors full observation flow.
        if (meta.has("observatory")) {
            Object obsAny = meta.get("observatory");
            if (!(obsAny instanceof JSONArray obsArr)) {
                throw new IllegalArgumentException("observatory must be an array");
            }
            if (obsArr.length() == 0) {
                throw new IllegalArgumentException("observatory array cannot be empty");
            }

            for (int i = 0; i < obsArr.length(); i++) {
                JSONObject obs = obsArr.getJSONObject(i);
                obs.getDouble("latitude");
                obs.getDouble("longitude");
                if (obs.has("observatory_name")) {
                    String name = obs.optString("observatory_name", "");
                    if (name.isBlank()) throw new IllegalArgumentException("observatory_name cannot be blank");
                }
            }
        }
    }

    private static void requireString(JSONObject obj, String key) {
        // reject missing, non-string, or blank values.
        if (!obj.has(key)) throw new IllegalArgumentException("Missing field: " + key);
        Object raw = obj.opt(key);
        if (!(raw instanceof String val) || val.isBlank()) {
            throw new IllegalArgumentException("Invalid field: " + key);
        }
    }

    private static void requireNumber(JSONObject obj, String key) {
        // getDouble throws on non-numeric values.
        if (!obj.has(key)) throw new IllegalArgumentException("Missing field: " + key);
        obj.getDouble(key);
    }

    private static void requireIsoInstant(JSONObject obj, String key) {
        // milliseconds format and parseability
        requireString(obj, key);
        String val = obj.getString(key);
        if (!STRICT_UTC_MILLIS.matcher(val).matches()) {
            throw new IllegalArgumentException("Invalid field: " + key);
        }
        try {
            Instant.parse(val);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid field: " + key);
        }
    }
}
