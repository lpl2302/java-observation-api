package com.o3.server;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class GraphQLClient {

    // reusable http client + fixed graphql endpoint.
    private final HttpClient client;
    private final URI endpoint;

    public GraphQLClient(String endpointUrl) {
        // parse endpoint once at construction.
        this.endpoint = URI.create(endpointUrl);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public JSONObject execute(String query, JSONObject variables) throws IOException, InterruptedException {
        // graphql request body format: { query, variables }.
        JSONObject payload = new JSONObject();
        payload.put("query", query);
        if (variables != null) payload.put("variables", variables);

        // send as utf-8 json payload to /graphql.
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            // non-200 means request is unusable for conversion flow.
            throw new IOException("GraphQL HTTP " + resp.statusCode());
        }

        // caller inspects "errors" and "data" fields.
        return new JSONObject(resp.body());
    }
}
