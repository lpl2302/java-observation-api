package com.o3.server;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class HttpUtil {
    // utility-only class.
    private HttpUtil() {}

    public static boolean isJsonContentType(String contentType) {
        // accept application/json with optional charset suffix.
        if (contentType == null) return false;
        return contentType.toLowerCase().contains("application/json");
    }

    public static String readBodyUtf8(HttpExchange exchange) throws IOException {
        // read whole request body as utf-8 text.
        try (InputStream is = exchange.getRequestBody();
             InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    public static void sendJson(HttpExchange exchange, int statusCode, String jsonText) throws IOException {
        // standard json response writer with proper content-type + length.
        if (jsonText == null) jsonText = "";
        byte[] bytes = jsonText.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendJsonObject(HttpExchange exchange, int statusCode, JSONObject obj) throws IOException {
        sendJson(exchange, statusCode, obj.toString());
    }

    public static void sendEmptyJsonObject(HttpExchange exchange, int statusCode) throws IOException {
        // useful for endpoints that only need status ack.
        sendJson(exchange, statusCode, "{}");
    }

    public static void sendNoContent(HttpExchange exchange) throws IOException {
        // explicit 204 with empty body.
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(204, 0);
        exchange.close();
    }

    public static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        // normalize errors to {"error":"..."} json.
        JSONObject err = new JSONObject();
        err.put("error", message == null ? "" : message);
        sendJsonObject(exchange, statusCode, err);
    }

    public static Map<String, String> parseQueryParams(URI uri) {
        // minimal query parser for endpoints like /datarecord?id=...
        Map<String, String> out = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isBlank()) return out;

        for (String pair : q.split("&")) {
            if (pair.isBlank()) continue;
            int idx = pair.indexOf('=');
            if (idx < 0) {
                out.put(urlDecode(pair), "");
            } else {
                out.put(urlDecode(pair.substring(0, idx)), urlDecode(pair.substring(idx + 1)));
            }
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // if decode fails, keep raw token.
            return s;
        }
    }
}
