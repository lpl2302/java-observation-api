package com.o3.server;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

final class ServerOutputSnapshot {

    private static final Path SNAPSHOT_PATH = Path.of("server_output.json");

    private ServerOutputSnapshot() {}

    static void write(MessageDatabase db, ObservationMapper mapper) {
        try {
            List<MessageDatabase.DbMessageRow> rows = db.readAllMessages();
            JSONArray arr = new JSONArray();
            for (MessageDatabase.DbMessageRow row : rows) {
                String nickname = db.getNickname(row.username);
                if (nickname == null || nickname.isBlank()) nickname = row.username;
                arr.put(mapper.toResponseObject(row, nickname));
            }

            Files.writeString(
                    SNAPSHOT_PATH,
                    arr.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Exception ignored) {
        }
    }
}
