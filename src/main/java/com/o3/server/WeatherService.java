package com.o3.server;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WeatherService {

    // extract pairs like <ParameterName>X</...><ParameterValue>Y</...>.
    private static final Pattern PARAM_PATTERN = Pattern.compile(
            "<BsWfs:ParameterName>([^<]+)</BsWfs:ParameterName>\\s*<BsWfs:ParameterValue>([^<]+)</BsWfs:ParameterValue>");

    // http client + base /wfs endpoint.
    private final HttpClient client;
    private final URI weatherEndpoint;

    public WeatherService() {
        this(resolveWeatherEndpoint());
    }

    public WeatherService(String weatherEndpointUrl) {
        this.weatherEndpoint = URI.create(weatherEndpointUrl);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static String resolveWeatherEndpoint() {
        // property wins over env, then fallback to assignment default.
        String prop = System.getProperty("WEATHER_URL");
        if (prop != null && !prop.isBlank()) return prop;

        String env = System.getenv("WEATHER_URL");
        if (env != null && !env.isBlank()) return env;

        return "http://localhost:4001/wfs";
    }

    public JSONObject generateWeather(double latitude, double longitude) throws IOException, InterruptedException {
        // server expects "latlon=lat,lon" + optional parameter list.
        String latlon = URLEncoder.encode(String.format(Locale.ROOT, "%.12f,%.12f", latitude, longitude),
                StandardCharsets.UTF_8);
        String parameters = URLEncoder.encode(
                "Temperature,TotalCloudCover,RadiationGlobalAccumulation", StandardCharsets.UTF_8);

        String url = weatherEndpoint + "?latlon=" + latlon + "&parameters=" + parameters;

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IOException("Weather server HTTP " + resp.statusCode());
        }

        // read values from xml and map into assignment keys.
        Map<String, Double> values = parseWeatherXml(resp.body());

        double temperatureC = required(values, "Temperature");
        double cloudCover = required(values, "TotalCloudCover");
        double radiation = required(values, "RadiationGlobalAccumulation");

        JSONObject w = new JSONObject();
        // weather server gives celsius -> api needs kelvins.
        w.put("temperature_in_kelvins", temperatureC + 273.15);
        // cloud cover is normalized to integer percentage.
        w.put("cloudiness_percentage", (int) Math.round(Math.max(0.0, Math.min(100.0, cloudCover))));
        // radiation value is forwarded as background_light_volume.
        w.put("background_light_volume", radiation);
        return w;
    }

    private double required(Map<String, Double> values, String key) throws IOException {
        // fail fast if any required weather value is missing.
        Double v = values.get(key);
        if (v == null || !Double.isFinite(v)) {
            throw new IOException("Weather server response missing: " + key);
        }
        return v;
    }

    Map<String, Double> parseWeatherXml(String xml) throws IOException {
        Map<String, Double> out = new HashMap<>();
        Matcher matcher = PARAM_PATTERN.matcher(xml == null ? "" : xml);
        while (matcher.find()) {
            // keep latest value per parameter name.
            String key = matcher.group(1).trim();
            String raw = matcher.group(2).trim();
            try {
                out.put(key, Double.parseDouble(raw));
            } catch (NumberFormatException ignored) {
                // ignore malformed value entries and continue.
            }
        }
        if (out.isEmpty()) throw new IOException("Weather server returned no parameter values");
        return out;
    }

    public JSONArray withWeather(JSONArray observatoryArray) throws IOException, InterruptedException {
        // iterate all observatories and enrich only requested ones.
        JSONArray out = new JSONArray();
        for (int i = 0; i < observatoryArray.length(); i++) {
            JSONObject obs = observatoryArray.getJSONObject(i);

            double lat = obs.getDouble("latitude");
            double lon = obs.getDouble("longitude");

            JSONObject copy = new JSONObject(obs.toString());
            // Feature 5: only entries that include "weather" in request should get generated weather.
            if (copy.has("weather")) {
                copy.put("weather", generateWeather(lat, lon));
            }
            out.put(copy);
        }
        return out;
    }
}
