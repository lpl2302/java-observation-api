package com.o3.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.sql.SQLException;
import java.util.regex.Pattern;

public class RegistrationHandler implements HttpHandler {

    // simple email format required by assignment.
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // db access for user creation.
    private final MessageDatabase db;

    public RegistrationHandler(MessageDatabase db) {
        this.db = db;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            // registration endpoint accepts post only.
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpUtil.sendError(exchange, 405, "Method not supported");
                return;
            }

            // body must be json.
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (!HttpUtil.isJsonContentType(contentType)) {
                HttpUtil.sendError(exchange, 415, "Content-Type must be application/json");
                return;
            }

            String body = HttpUtil.readBodyUtf8(exchange);
            JSONObject json = new JSONObject(body);

            // trim and validate required registration fields.
            String username = json.optString("username", "").trim();
            String password = json.optString("password", "").trim();
            String email = json.optString("email", "").trim();
            String nickname = json.optString("nickname", "").trim();

            if (username.isBlank() || password.isBlank() || email.isBlank() || nickname.isBlank()) {
                HttpUtil.sendError(exchange, 400, "Missing or invalid fields");
                return;
            }
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                HttpUtil.sendError(exchange, 400, "Invalid email");
                return;
            }

            // reject duplicates on username or email.
            boolean added = db.insertUser(new User(username, password, email, nickname));
            if (!added) {
                HttpUtil.sendError(exchange, 409, "User already registered");
                return;
            }

            // success payload is an empty json object.
            HttpUtil.sendEmptyJsonObject(exchange, 200);

        } catch (IllegalArgumentException iae) {
            // schema/value validation problems.
            HttpUtil.sendError(exchange, 400, iae.getMessage());
        } catch (SQLException sqle) {
            // unexpected db issue.
            HttpUtil.sendError(exchange, 500, "Database error");
        } catch (Exception e) {
            // fallback for malformed json bodies.
            HttpUtil.sendError(exchange, 400, "Invalid JSON or missing fields");
        }
    }
}
