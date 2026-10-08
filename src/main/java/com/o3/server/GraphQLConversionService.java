package com.o3.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

public class GraphQLConversionService {

    // fixed query that matches the provided conversion graphql server.
    private static final String CONVERT_QUERY = """
            query Convert($input: ConvertInput!) {
              convert(input: $input) {
                data {
                  orbital_elements {
                    semi_major_axis_au
                    eccentricity
                    inclination_deg
                    longitude_ascending_node_deg
                    argument_of_periapsis_deg
                    mean_anomaly_deg
                  }
                  state_vector {
                    position_au
                    velocity_au_per_day
                  }
                }
                warnings
                errors
              }
            }
            """;

    // http adapter for talking to conversion backend.
    private final GraphQLClient client;

    public GraphQLConversionService(String endpointUrl) {
        this(new GraphQLClient(endpointUrl));
    }

    GraphQLConversionService(GraphQLClient client) {
        this.client = client;
    }

    public JSONObject convertPartialObservation(JSONObject partial) throws IOException, InterruptedException {
        // partial record must contain exactly one orbit representation.
        boolean hasOrbital = partial.has("orbital_elements");
        boolean hasState = partial.has("state_vector");
        if (hasOrbital == hasState) {
            throw new IllegalArgumentException("Partial observation must contain exactly one of orbital_elements or state_vector");
        }

        // send only the conversion input object expected by graphql.
        JSONObject input = new JSONObject();
        if (hasOrbital) input.put("orbital_elements", partial.getJSONObject("orbital_elements"));
        if (hasState) input.put("state_vector", partial.getJSONObject("state_vector"));

        JSONObject variables = new JSONObject();
        variables.put("input", input);

        JSONObject response = client.execute(CONVERT_QUERY, variables);
        if (response.has("errors")) {
            // top-level graphql protocol errors.
            throw new IOException("GraphQL execution failed");
        }

        JSONObject data = response.optJSONObject("data");
        JSONObject convert = data == null ? null : data.optJSONObject("convert");
        if (convert == null) {
            throw new IOException("GraphQL response missing convert payload");
        }

        // domain-level errors are returned inside convert.errors.
        JSONArray convertErrors = convert.optJSONArray("errors");
        if (convertErrors != null && convertErrors.length() > 0) {
            throw new IllegalArgumentException(convertErrors.optString(0, "GraphQL conversion failed"));
        }

        JSONObject convertedData = convert.optJSONObject("data");
        if (convertedData == null) {
            throw new IOException("GraphQL response missing converted data");
        }

        JSONObject out = new JSONObject(partial.toString());
        if (hasOrbital) {
            // input had orbital -> enrich with state vector.
            JSONObject state = optObject(convertedData, "state_vector");
            if (state == null) throw new IOException("GraphQL did not return state_vector");
            out.put("state_vector", state);
        } else {
            // input had state vector -> enrich with orbital elements.
            JSONObject orbital = optObject(convertedData, "orbital_elements");
            if (orbital == null) throw new IOException("GraphQL did not return orbital_elements");
            out.put("orbital_elements", orbital);
        }

        return out;
    }

    private JSONObject optObject(JSONObject obj, String key) {
        // guard helper to keep casts centralized.
        Object value = obj.opt(key);
        if (value instanceof JSONObject jsonObject) return jsonObject;
        return null;
    }
}
