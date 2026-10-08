package com.o3.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class DataRecordHandler implements HttpHandler {

    // central handler for feature 1/2/4/5/7.
    private final MessageDatabase db;
    private final ObservationMapper mapper;
    private final WeatherService weatherService;

    public DataRecordHandler(MessageDatabase db, ObservationMapper mapper, WeatherService weatherService) {
        this.db = db;
        this.mapper = mapper;
        this.weatherService = weatherService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // dispatch by http method.
        String method = exchange.getRequestMethod();

        try {
            if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
            } else if ("PUT".equalsIgnoreCase(method)) {
                handlePut(exchange);
            } else {
                HttpUtil.sendError(exchange, 405, "Method not supported");
            }
        } catch (SecurityException se) {
            // standardized unauthenticated response.
            HttpUtil.sendError(exchange, 401, "Unauthorized");
        } catch (SQLException sqle) {
            HttpUtil.sendError(exchange, 500, "Database error");
        } catch (IllegalArgumentException iae) {
            HttpUtil.sendError(exchange, 400, iae.getMessage());
        } catch (Exception e) {
            HttpUtil.sendError(exchange, 400, "Bad request");
        }
    }

    private void requireAuthenticated(HttpExchange exchange) {
        // java httpserver authenticator sets principal when credentials are valid.
        if (exchange.getPrincipal() == null) {
            throw new SecurityException("Unauthorized");
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException, SQLException, InterruptedException {
        requireAuthenticated(exchange);

        // post requires application/json body.
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!HttpUtil.isJsonContentType(contentType)) {
            HttpUtil.sendError(exchange, 415, "Content-Type must be application/json");
            return;
        }

        String username = exchange.getPrincipal().getUsername();

        String body = HttpUtil.readBodyUtf8(exchange);
        JSONObject json = new JSONObject(body);

        // validate required observation structure.
        ObservationValidator.validateObservationForCreateOrUpdate(json, false);

        JSONObject meta = json.getJSONObject("metadata");

        // feature 5 trigger: any observatory item containing "weather" key.
        boolean wantsWeather = false;
        if (meta.has("observatory")) {
            JSONArray obsArr = meta.optJSONArray("observatory");
            if (obsArr != null) {
                for (int i = 0; i < obsArr.length(); i++) {
                    JSONObject obs = obsArr.getJSONObject(i);
                    if (obs.has("weather")) {
                        wantsWeather = true;
                        break;
                    }
                }
            }
        }

        String payload = meta.getString("record_payload");

        if (wantsWeather) {
            // if id is given, treat as "add weather to existing message".
            if (meta.has("id")) {
                long messageId;
                try {
                    messageId = meta.getLong("id");
                } catch (Exception e) {
                    HttpUtil.sendError(exchange, 400, "Invalid id");
                    return;
                }
                if (messageId <= 0) {
                    HttpUtil.sendError(exchange, 400, "Invalid id");
                    return;
                }

                if (!db.messageExists(messageId)) {
                    HttpUtil.sendError(exchange, 404, "Message not found");
                    return;
                }

                // only original owner can update existing message weather.
                String owner = db.getMessageOwner(messageId);
                if (!owner.equals(username)) {
                    HttpUtil.sendError(exchange, 403, "Only the message owner can modify weather");
                    return;
                }

                MessageDatabase.DbMessageRow row = db.readMessageById(messageId);
                JSONObject stored = new JSONObject(row.messageJson);

                JSONObject storedMeta = stored.optJSONObject("metadata");
                if (storedMeta == null) storedMeta = new JSONObject();

                // prefer observatory array from request; fallback to stored one.
                JSONArray obs = meta.optJSONArray("observatory");
                if (obs == null || obs.length() == 0) {
                    obs = storedMeta.optJSONArray("observatory");
                }

                if (obs == null || obs.length() == 0) {
                    HttpUtil.sendError(exchange, 400, "observatory required when adding weather");
                    return;
                }

                storedMeta.put("observatory", weatherService.withWeather(obs));
                stored.put("metadata", storedMeta);

                // update db row + snapshot.
                db.updateMessage(messageId, stored.toString(), payload);
                ServerOutputSnapshot.write(db, mapper);

                HttpUtil.sendEmptyJsonObject(exchange, 200);
                return;
            }

            if (meta.has("observatory")) {
                JSONArray obsArr = meta.optJSONArray("observatory");
                if (obsArr != null && obsArr.length() > 0) {
                    // enrich only entries that requested weather.
                    meta.put("observatory", weatherService.withWeather(obsArr));
                }
            }

            // strip server-managed metadata fields before insert.
            meta.remove("record_time_received");
            meta.remove("record_owner");
            meta.remove("id");

            long now = System.currentTimeMillis();
            db.insertMessage(username, now, json.toString(), payload);
            ServerOutputSnapshot.write(db, mapper);

            HttpUtil.sendEmptyJsonObject(exchange, 200);
            return;
        }

        meta.remove("record_time_received");
        meta.remove("record_owner");
        meta.remove("id");

        // normal observation insert (feature 1/4).
        long now = System.currentTimeMillis();
        db.insertMessage(username, now, json.toString(), payload);
        ServerOutputSnapshot.write(db, mapper);

        HttpUtil.sendEmptyJsonObject(exchange, 201);
    }

    private void handleGet(HttpExchange exchange) throws IOException, SQLException {
        requireAuthenticated(exchange);

        // return 204 when there are no rows.
        List<MessageDatabase.DbMessageRow> rows = db.readAllMessages();
        if (rows.isEmpty()) {
            HttpUtil.sendNoContent(exchange);
            return;
        }

        // map each db row to assignment response format.
        JSONArray arr = new JSONArray();
        for (MessageDatabase.DbMessageRow row : rows) {
            String nickname = db.getNickname(row.username);
            if (nickname == null || nickname.isBlank()) nickname = row.username;

            arr.put(mapper.toResponseObject(row, nickname));
        }

        HttpUtil.sendJson(exchange, 200, arr.toString());
    }

    private void handlePut(HttpExchange exchange) throws IOException, SQLException {
        requireAuthenticated(exchange);

        // update endpoint requires json body.
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!HttpUtil.isJsonContentType(contentType)) {
            HttpUtil.sendError(exchange, 415, "Content-Type must be application/json");
            return;
        }

        Map<String, String> params = HttpUtil.parseQueryParams(exchange.getRequestURI());
        String idStr = params.get("id");
        if (idStr == null || idStr.isBlank()) {
            HttpUtil.sendError(exchange, 400, "Missing id query parameter");
            return;
        }

        long id;
        try {
            id = Long.parseLong(idStr);
        } catch (NumberFormatException nfe) {
            HttpUtil.sendError(exchange, 400, "Invalid id");
            return;
        }

        MessageDatabase.DbMessageRow existing = db.readMessageById(id);
        if (existing == null) {
            HttpUtil.sendError(exchange, 404, "Message not found");
            return;
        }

        // update allowed only for original owner.
        String username = exchange.getPrincipal().getUsername();
        if (!existing.username.equals(username)) {
            HttpUtil.sendError(exchange, 403, "Only the message owner can update the message");
            return;
        }

        String body = HttpUtil.readBodyUtf8(exchange);
        JSONObject updated = new JSONObject(body);

        // put requires full observation payload, not partial patch.
        ObservationValidator.validateObservationForCreateOrUpdate(updated, true);

        JSONObject meta = updated.getJSONObject("metadata");
        if (meta.has("update_reason") && !(meta.get("update_reason") instanceof String)) {
            HttpUtil.sendError(exchange, 400, "Invalid field: update_reason");
            return;
        }
        // if body includes metadata.id, it must match query id.
        if (meta.has("id")) {
            long bodyId;
            try {
                bodyId = meta.getLong("id");
            } catch (Exception e) {
                HttpUtil.sendError(exchange, 400, "Invalid id");
                return;
            }
            if (bodyId != id) {
                HttpUtil.sendError(exchange, 400, "id in metadata must match id query parameter");
                return;
            }
        }
        String recordPayload = meta.getString("record_payload");
        // default update reason is required output behavior.
        String updateReason = meta.optString("update_reason", "N/A");
        if (updateReason == null || updateReason.isBlank()) updateReason = "N/A";

        meta.put("update_reason", updateReason);
        meta.put("edited", ObservationValidator.nowUtcMillisIso());

        meta.remove("record_owner");
        meta.remove("record_time_received");
        meta.remove("id");

        // store updated payload and metadata, then refresh snapshot.
        db.updateMessage(id, updated.toString(), recordPayload);
        ServerOutputSnapshot.write(db, mapper);

        MessageDatabase.DbMessageRow after = db.readMessageById(id);
        String nickname = db.getNickname(after.username);
        if (nickname == null || nickname.isBlank()) nickname = after.username;

        JSONObject responseObj = mapper.toResponseObject(after, nickname);
        HttpUtil.sendJson(exchange, 200, responseObj.toString());
    }
}
