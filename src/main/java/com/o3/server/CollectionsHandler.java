package com.o3.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class CollectionsHandler implements HttpHandler {

    // db + mapper for collection operations.
    private final MessageDatabase db;
    private final ObservationMapper mapper;

    public CollectionsHandler(MessageDatabase db, ObservationMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            // all collection endpoints require auth.
            if (exchange.getPrincipal() == null) {
                HttpUtil.sendError(exchange, 401, "Unauthorized");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String username = exchange.getPrincipal().getUsername();

            // list user collections.
            if (path.equals("/collections") || path.equals("/collections/")) {
                if (!"GET".equalsIgnoreCase(method)) {
                    HttpUtil.sendError(exchange, 405, "Method not supported");
                    return;
                }
                handleListCollections(exchange, username);
                return;
            }

            // create collection, optional initial message id list.
            if (path.equals("/collections/create")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    HttpUtil.sendError(exchange, 405, "Method not supported");
                    return;
                }
                handleCreate(exchange, username);
                return;
            }

            // add message ids to existing collection.
            if (path.equals("/collections/add")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    HttpUtil.sendError(exchange, 405, "Method not supported");
                    return;
                }
                handleAdd(exchange, username);
                return;
            }

            // fetch messages in one collection id.
            if (path.startsWith("/collections/")) {
                if (!"GET".equalsIgnoreCase(method)) {
                    HttpUtil.sendError(exchange, 405, "Method not supported");
                    return;
                }
                String idPart = path.substring("/collections/".length());
                if (idPart.endsWith("/")) {
                    // tolerate trailing slash: /collections/7/
                    idPart = idPart.substring(0, idPart.length() - 1);
                }
                if (idPart.isBlank()) {
                    HttpUtil.sendError(exchange, 404, "Not found");
                    return;
                }
                long collectionId = Long.parseLong(idPart);
                handleGetCollection(exchange, username, collectionId);
                return;
            }

            HttpUtil.sendError(exchange, 404, "Not found");

        } catch (IllegalArgumentException iae) {
            // use 404 for unknown collection ids as expected by tests.
            HttpUtil.sendError(exchange, 404, iae.getMessage());
        } catch (SQLException sqle) {
            HttpUtil.sendError(exchange, 500, "Database error");
        } catch (Exception e) {
            HttpUtil.sendError(exchange, 400, "Bad request");
        }
    }

    private void requireJsonContentType(HttpExchange exchange) throws IOException {
        // helper for post endpoints that require json body.
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (!HttpUtil.isJsonContentType(contentType)) {
            HttpUtil.sendError(exchange, 415, "Content-Type must be application/json");
            throw new IllegalArgumentException("Bad Content-Type");
        }
    }

    private void handleListCollections(HttpExchange exchange, String username) throws SQLException, IOException {
        // response is plain json array of collection ids.
        List<Long> ids = db.listCollectionIds(username);
        JSONArray arr = new JSONArray();
        for (Long id : ids) arr.put(id);
        HttpUtil.sendJson(exchange, 200, arr.toString());
    }

    private void handleCreate(HttpExchange exchange, String username) throws SQLException, IOException {
        requireJsonContentType(exchange);

        // body may be blank or an array of initial message ids.
        String body = HttpUtil.readBodyUtf8(exchange).trim();
        List<Long> initialIds = new ArrayList<>();

        if (!body.isBlank()) {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                initialIds.add(arr.getLong(i));
            }
        }

        long newId = db.createCollection(username);

        // add initial messages if request supplied them.
        if (!initialIds.isEmpty()) {
            db.addMessagesToCollection(username, newId, initialIds);
        }

        JSONObject resp = new JSONObject();
        resp.put("created_collection_id", newId);
        HttpUtil.sendJson(exchange, 200, resp.toString());
    }

    private void handleAdd(HttpExchange exchange, String username) throws SQLException, IOException {
        requireJsonContentType(exchange);

        // expected: { collection_id, message_ids[] }.
        String body = HttpUtil.readBodyUtf8(exchange);
        JSONObject json = new JSONObject(body);

        long collectionId = json.getLong("collection_id");
        JSONArray arr = json.getJSONArray("message_ids");

        List<Long> messageIds = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            messageIds.add(arr.getLong(i));
        }

        db.addMessagesToCollection(username, collectionId, messageIds);
        HttpUtil.sendEmptyJsonObject(exchange, 200);
    }

    private void handleGetCollection(HttpExchange exchange, String username, long collectionId) throws SQLException, IOException {
        // fetch rows in collection and map to normal observation response shape.
        List<MessageDatabase.DbMessageRow> rows = db.readMessagesInCollection(username, collectionId);
        JSONArray out = new JSONArray();

        for (MessageDatabase.DbMessageRow row : rows) {
            String nickname = db.getNickname(row.username);
            if (nickname == null || nickname.isBlank()) nickname = row.username;
            out.put(mapper.toResponseObject(row, nickname));
        }

        HttpUtil.sendJson(exchange, 200, out.toString());
    }
}
