package com.o3.server;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTests {

    @Test
    void weatherXmlParserExtractsRequiredParameters() throws Exception {
        String xml = """
                <wfs:FeatureCollection>
                  <wfs:member>
                    <BsWfs:BsWfsElement>
                      <BsWfs:ParameterName>Temperature</BsWfs:ParameterName>
                      <BsWfs:ParameterValue>-0.1</BsWfs:ParameterValue>
                    </BsWfs:BsWfsElement>
                  </wfs:member>
                  <wfs:member>
                    <BsWfs:BsWfsElement>
                      <BsWfs:ParameterName>TotalCloudCover</BsWfs:ParameterName>
                      <BsWfs:ParameterValue>44.6</BsWfs:ParameterValue>
                    </BsWfs:BsWfsElement>
                  </wfs:member>
                  <wfs:member>
                    <BsWfs:BsWfsElement>
                      <BsWfs:ParameterName>RadiationGlobalAccumulation</BsWfs:ParameterName>
                      <BsWfs:ParameterValue>193.0</BsWfs:ParameterValue>
                    </BsWfs:BsWfsElement>
                  </wfs:member>
                </wfs:FeatureCollection>
                """;

        WeatherService service = new WeatherService("http://localhost:4001/wfs");
        Map<String, Double> parsed = service.parseWeatherXml(xml);

        assertEquals(-0.1, parsed.get("Temperature"));
        assertEquals(44.6, parsed.get("TotalCloudCover"));
        assertEquals(193.0, parsed.get("RadiationGlobalAccumulation"));
    }

    @Test
    void weatherIsAddedOnlyToObservatoriesThatRequestIt() throws Exception {
        WeatherService service = new WeatherService("http://localhost:4001/wfs") {
            @Override
            public JSONObject generateWeather(double latitude, double longitude) {
                return new JSONObject()
                        .put("temperature_in_kelvins", 300.0)
                        .put("cloudiness_percentage", 50)
                        .put("background_light_volume", 10.0);
            }
        };

        JSONObject withWeather = new JSONObject()
                .put("latitude", 1.0)
                .put("longitude", 2.0)
                .put("weather", new JSONObject());
        JSONObject withoutWeather = new JSONObject()
                .put("latitude", 1.0)
                .put("longitude", 2.0);

        org.json.JSONArray input = new org.json.JSONArray()
                .put(withWeather)
                .put(withoutWeather);

        org.json.JSONArray out = service.withWeather(input);
        assertTrue(out.getJSONObject(0).has("weather"));
        assertTrue(!out.getJSONObject(1).has("weather"));
    }

    @Test
    void graphQlConversionAddsStateVectorWhenOrbitalElementsProvided() throws Exception {
        JSONObject graphqlResponse = new JSONObject("""
                {
                  "data": {
                    "convert": {
                      "data": {
                        "orbital_elements": null,
                        "state_vector": {
                          "position_au": [1.0, 2.0, 3.0],
                          "velocity_au_per_day": [0.1, 0.2, 0.3]
                        }
                      },
                      "warnings": [],
                      "errors": []
                    }
                  }
                }
                """);

        GraphQLConversionService service = new GraphQLConversionService(
                new StubGraphQlClient(graphqlResponse));

        JSONObject partial = new JSONObject("""
                {
                  "target_body_name": "Moon 301",
                  "center_body_name": "Earth 399",
                  "epoch": "2025-01-01T00:00:00.000Z",
                  "orbital_elements": {
                    "semi_major_axis_au": 1.458,
                    "eccentricity": 0.223,
                    "inclination_deg": 10.829,
                    "longitude_ascending_node_deg": 304.3,
                    "argument_of_periapsis_deg": 178.7,
                    "mean_anomaly_deg": 120.5
                  },
                  "metadata": {
                    "record_payload": "payload"
                  }
                }
                """);

        JSONObject full = service.convertPartialObservation(partial);
        assertTrue(full.has("orbital_elements"));
        assertTrue(full.has("state_vector"));
        assertEquals(3, full.getJSONObject("state_vector").getJSONArray("position_au").length());
    }

    @Test
    void graphQlConversionThrowsWhenGraphQlReturnsDomainErrors() {
        JSONObject graphqlResponse = new JSONObject("""
                {
                  "data": {
                    "convert": {
                      "data": null,
                      "warnings": [],
                      "errors": ["Provide at least one of state_vector or orbital_elements."]
                    }
                  }
                }
                """);

        GraphQLConversionService service = new GraphQLConversionService(
                new StubGraphQlClient(graphqlResponse));

        JSONObject invalid = new JSONObject("""
                {
                  "target_body_name": "Moon 301",
                  "center_body_name": "Earth 399",
                  "epoch": "2025-01-01T00:00:00.000Z",
                  "orbital_elements": {
                    "semi_major_axis_au": 1.458,
                    "eccentricity": 0.223,
                    "inclination_deg": 10.829,
                    "longitude_ascending_node_deg": 304.3,
                    "argument_of_periapsis_deg": 178.7,
                    "mean_anomaly_deg": 120.5
                  },
                  "metadata": {
                    "record_payload": "payload"
                  }
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> service.convertPartialObservation(invalid));
    }

    @Test
    void messageDatabaseCollectionsRejectUnknownMessagesAndCollections() throws Exception {
        Path dbPath = Files.createTempFile("server-tests-", ".db");
        MessageDatabase db = MessageDatabase.getInstance();

        try {
            db.open(dbPath.toString());
            db.insertUser(new User("alice", "password123", "alice@example.com", "Alice"));
            long msgId = db.insertMessage("alice", System.currentTimeMillis(),
                    "{\"metadata\":{\"record_payload\":\"p\"}}", "p");
            assertTrue(msgId > 0);

            long colId = db.createCollection("alice");
            assertTrue(colId > 0);

            IllegalArgumentException missingMessage = assertThrows(IllegalArgumentException.class,
                    () -> db.addMessagesToCollection("alice", colId, java.util.List.of(99999L)));
            assertTrue(missingMessage.getMessage().contains("Message id does not exist"));

            IllegalArgumentException missingCollection = assertThrows(IllegalArgumentException.class,
                    () -> db.readMessagesInCollection("alice", 99999L));
            assertTrue(missingCollection.getMessage().contains("Collection not found"));
        } finally {
            try {
                db.close();
            } catch (SQLException ignored) {
            }
            Files.deleteIfExists(dbPath);
        }
    }

    @Test
    void validatorRejectsNonStringPayloadAndInvalidEpoch() {
        JSONObject invalidPayload = new JSONObject("""
                {
                  "target_body_name": "Moon 301",
                  "center_body_name": "Earth 399",
                  "epoch": "2025-01-01T00:00:00.000Z",
                  "orbital_elements": {
                    "semi_major_axis_au": 1.458,
                    "eccentricity": 0.223,
                    "inclination_deg": 10.829,
                    "longitude_ascending_node_deg": 304.3,
                    "argument_of_periapsis_deg": 178.7,
                    "mean_anomaly_deg": 120.5
                  },
                  "metadata": {
                    "record_payload": 123
                  }
                }
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ObservationValidator.validateObservationForCreateOrUpdate(invalidPayload, false));

        JSONObject invalidEpoch = new JSONObject("""
                {
                  "target_body_name": "Moon 301",
                  "center_body_name": "Earth 399",
                  "epoch": "not-a-date",
                  "orbital_elements": {
                    "semi_major_axis_au": 1.458,
                    "eccentricity": 0.223,
                    "inclination_deg": 10.829,
                    "longitude_ascending_node_deg": 304.3,
                    "argument_of_periapsis_deg": 178.7,
                    "mean_anomaly_deg": 120.5
                  },
                  "metadata": {
                    "record_payload": "ok"
                  }
                }
                """);

        assertThrows(IllegalArgumentException.class,
                () -> ObservationValidator.validateObservationForCreateOrUpdate(invalidEpoch, false));
    }

    private static class StubGraphQlClient extends GraphQLClient {
        private final JSONObject response;

        StubGraphQlClient(JSONObject response) {
            super("http://localhost:4003/graphql");
            this.response = response;
        }

        @Override
        public JSONObject execute(String query, JSONObject variables) throws IOException, InterruptedException {
            assertNotNull(query);
            assertNotNull(variables);
            JSONObject input = variables.optJSONObject("input");
            assertNotNull(input);
            return new JSONObject(response.toString());
        }
    }
}
