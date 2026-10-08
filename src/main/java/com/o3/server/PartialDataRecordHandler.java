package com.o3.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.sql.SQLException;

public class PartialDataRecordHandler implements HttpHandler {

    // db write access + conversion service + response mapper.
    private final MessageDatabase db;
    private final GraphQLConversionService conversionService;
    private final ObservationMapper mapper;

    public PartialDataRecordHandler(MessageDatabase db,
                                    GraphQLConversionService conversionService,
                                    ObservationMapper mapper) {
        this.db = db;
        this.conversionService = conversionService;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            // endpoint is authenticated.
            if (exchange.getPrincipal() == null) {
                HttpUtil.sendError(exchange, 401, "Unauthorized");
                return;
            }

            // partial endpoint accepts post only.
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtil.sendError(exchange, 405, "Method not supported");
                return;
            }

            // enforce json requests.
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (!HttpUtil.isJsonContentType(contentType)) {
                HttpUtil.sendError(exchange, 415, "Content-Type must be application/json");
                return;
            }

            String username = exchange.getPrincipal().getUsername();

            String body = HttpUtil.readBodyUtf8(exchange);
            JSONObject partial = new JSONObject(body);

            // must contain exactly one of orbital/state vector.
            ObservationValidator.validateObservationForPartial(partial);

            // call external graphql converter.
            JSONObject full = conversionService.convertPartialObservation(partial);

            if (!full.has("orbital_elements") || !full.has("state_vector")) {
                HttpUtil.sendError(exchange, 400, "GraphQL conversion did not produce full observation");
                return;
            }

            // clean server-managed metadata before storing.
            JSONObject meta = full.getJSONObject("metadata");
            String payload = meta.getString("record_payload");
            meta.remove("record_time_received");
            meta.remove("record_owner");
            meta.remove("id");

            // insert converted record and refresh persistence snapshot.
            long now = System.currentTimeMillis();
            db.insertMessage(username, now, full.toString(), payload);
            ServerOutputSnapshot.write(db, mapper);

            HttpUtil.sendEmptyJsonObject(exchange, 200);

        } catch (IllegalArgumentException iae) {
            // validation and domain errors.
            HttpUtil.sendError(exchange, 400, iae.getMessage());
        } catch (IOException | InterruptedException ioe) {
            // conversion backend communication errors.
            HttpUtil.sendError(exchange, 400, "Failed to convert partial observation");
        } catch (SQLException sqle) {
            HttpUtil.sendError(exchange, 500, "Database error");
        } catch (Exception e) {
            HttpUtil.sendError(exchange, 400, "Bad request");
        }
    }
}
