package com.o3.server;

import org.apache.commons.codec.digest.Crypt;

import java.io.File;
import java.security.SecureRandom;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MessageDatabase {

    // singleton used by all handlers.
    private static MessageDatabase instance;
    private Connection connection;

    // secure rng for password salt generation.
    private final SecureRandom secureRandom = new SecureRandom();

    // Valid salt characters for SHA-crypt
    private static final char[] SALT_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789./".toCharArray();

    private MessageDatabase() {}

    public static synchronized MessageDatabase getInstance() {
        if (instance == null) instance = new MessageDatabase();
        return instance;
    }

    public synchronized void open(String dbFilePath) throws SQLException {
        // open sqlite file and initialize schema.
        if (dbFilePath == null || dbFilePath.isBlank()) {
            throw new SQLException("Database path is blank");
        }

        File dbFile = new File(dbFilePath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFilePath);

        // (Optional) Foreign keys on
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
        } catch (Exception ignored) {}

        initializeDatabase();
    }

    public synchronized void close() throws SQLException {
        // close shared db connection on shutdown.
        if (connection != null && !connection.isClosed()) connection.close();
    }

    private synchronized void initializeDatabase() throws SQLException {
        // create all required tables if they do not exist yet.
        try (Statement st = connection.createStatement()) {

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS users (" +
                            "username TEXT PRIMARY KEY, " +
                            "password TEXT NOT NULL, " +
                            "email TEXT NOT NULL UNIQUE, " +
                            "nickname TEXT NOT NULL" +
                            ")"
            );

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS messages (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "username TEXT NOT NULL, " +
                            "record_time_received INTEGER NOT NULL, " +
                            "record_payload TEXT NOT NULL, " +
                            "message_json TEXT NOT NULL, " +
                            "FOREIGN KEY(username) REFERENCES users(username)" +
                            ")"
            );

            // Feature 6: collections
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS collections (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "owner_username TEXT NOT NULL, " +
                            "FOREIGN KEY(owner_username) REFERENCES users(username)" +
                            ")"
            );

            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS collection_messages (" +
                            "collection_id INTEGER NOT NULL, " +
                            "message_id INTEGER NOT NULL, " +
                            "PRIMARY KEY(collection_id, message_id), " +
                            "FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE, " +
                            "FOREIGN KEY(message_id) REFERENCES messages(id) ON DELETE CASCADE" +
                            ")"
            );
        }
    }

    private String generateSalt(int length) {
        // produce random salt from valid sha-crypt alphabet.
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SALT_CHARS[secureRandom.nextInt(SALT_CHARS.length)]);
        }
        return sb.toString();
    }

    // ---------------- USERS ----------------

    private synchronized boolean usernameExists(String username) throws SQLException {
        // helper for unique username check.
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private synchronized boolean emailExists(String email) throws SQLException {
        // helper for unique email check.
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized boolean insertUser(User user) throws SQLException {
        // enforce uniqueness before insert.
        if (usernameExists(user.getUsername())) return false;
        if (emailExists(user.getEmail())) return false;

        // Hash + salt password
        String salt = "$6$" + generateSalt(16); // "$6$" = SHA-512 crypt
        String hashedPassword = Crypt.crypt(user.getPassword(), salt);

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users(username,password,email,nickname) VALUES(?,?,?,?)")) {
            ps.setString(1, user.getUsername());
            ps.setString(2, hashedPassword);
            ps.setString(3, user.getEmail());
            ps.setString(4, user.getNickname());
            ps.executeUpdate();
            return true;
        }
    }

    public synchronized boolean validateCredentials(String username, String password) throws SQLException {
        // verify provided password against stored hash.
        try (PreparedStatement ps = connection.prepareStatement("SELECT password FROM users WHERE username = ?")) {
            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;

                String storedHash = rs.getString("password");
                String computed = Crypt.crypt(password, storedHash);
                return storedHash.equals(computed);
            }
        }
    }

    public synchronized String getNickname(String username) throws SQLException {
        // return null if user does not exist.
        try (PreparedStatement ps = connection.prepareStatement("SELECT nickname FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("nickname") : null;
            }
        }
    }

    // ---------------- MESSAGES ----------------

    public synchronized long insertMessage(String username,
                                          long recordTimeReceived,
                                          String messageJson,
                                          String recordPayload) throws SQLException {
        // store full observation json + extracted payload for quick reads.

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO messages(username,record_time_received,record_payload,message_json) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, username);
            ps.setLong(2, recordTimeReceived);
            ps.setString(3, recordPayload);
            ps.setString(4, messageJson);

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated id returned");
            }
        }
    }

    public synchronized boolean messageExists(long id) throws SQLException {
        // existence check used by update/collections logic.
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM messages WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized String getMessageOwner(long id) throws SQLException {
        // ownership guard for put/weather update operations.
        try (PreparedStatement ps = connection.prepareStatement("SELECT username FROM messages WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("username") : null;
            }
        }
    }

    public synchronized void updateMessage(long id, String messageJson, String recordPayload) throws SQLException {
        // replace stored json + payload by message id.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE messages SET record_payload = ?, message_json = ? WHERE id = ?")) {
            ps.setString(1, recordPayload);
            ps.setString(2, messageJson);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public static class DbMessageRow {
        public long id;
        public String username;
        public long recordTimeReceived;
        public String messageJson;
        public String recordPayload;
    }

    public synchronized DbMessageRow readMessageById(long id) throws SQLException {
        // fetch one row for ownership/update flows.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, username, record_time_received, record_payload, message_json FROM messages WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                DbMessageRow row = new DbMessageRow();
                row.id = rs.getLong("id");
                row.username = rs.getString("username");
                row.recordTimeReceived = rs.getLong("record_time_received");
                row.recordPayload = rs.getString("record_payload");
                row.messageJson = rs.getString("message_json");
                return row;
            }
        }
    }

    public synchronized List<DbMessageRow> readAllMessages() throws SQLException {
        // return oldest-first for stable output ordering.
        List<DbMessageRow> out = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, username, record_time_received, record_payload, message_json FROM messages ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                DbMessageRow row = new DbMessageRow();
                row.id = rs.getLong("id");
                row.username = rs.getString("username");
                row.recordTimeReceived = rs.getLong("record_time_received");
                row.recordPayload = rs.getString("record_payload");
                row.messageJson = rs.getString("message_json");
                out.add(row);
            }
        }
        return out;
    }

    // ---------------- COLLECTIONS (Feature 6) ----------------

    public synchronized long createCollection(String ownerUsername) throws SQLException {
        // create collection owned by one user.
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO collections(owner_username) VALUES(?)",
                Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, ownerUsername);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated collection id");
            }
        }
    }

    public synchronized boolean collectionExistsForUser(String ownerUsername, long collectionId) throws SQLException {
        // enforce user-scoped access to collections.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM collections WHERE id = ? AND owner_username = ?")) {
            ps.setLong(1, collectionId);
            ps.setString(2, ownerUsername);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized List<Long> listCollectionIds(String ownerUsername) throws SQLException {
        // list collection ids for one owner in ascending order.
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM collections WHERE owner_username = ? ORDER BY id ASC")) {
            ps.setString(1, ownerUsername);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong("id"));
            }
        }
        return out;
    }

    public synchronized void addMessagesToCollection(String ownerUsername, long collectionId, List<Long> messageIds)
            throws SQLException {
        // check collection ownership first.

        if (!collectionExistsForUser(ownerUsername, collectionId)) {
            throw new IllegalArgumentException("Collection not found for user");
        }

        // Validate message IDs exist (safer for unit tests)
        for (Long mid : messageIds) {
            if (mid == null) continue;
            if (!messageExists(mid)) {
                throw new IllegalArgumentException("Message id does not exist: " + mid);
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO collection_messages(collection_id, message_id) VALUES(?,?)")) {
            for (Long mid : messageIds) {
                if (mid == null) continue;
                ps.setLong(1, collectionId);
                ps.setLong(2, mid);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public synchronized List<DbMessageRow> readMessagesInCollection(String ownerUsername, long collectionId)
            throws SQLException {
        // return all messages linked to one user-owned collection.

        if (!collectionExistsForUser(ownerUsername, collectionId)) {
            throw new IllegalArgumentException("Collection not found for user");
        }

        List<DbMessageRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT m.id, m.username, m.record_time_received, m.record_payload, m.message_json " +
                        "FROM collection_messages cm " +
                        "JOIN messages m ON m.id = cm.message_id " +
                        "WHERE cm.collection_id = ? " +
                        "ORDER BY m.id ASC")) {
            ps.setLong(1, collectionId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DbMessageRow row = new DbMessageRow();
                    row.id = rs.getLong("id");
                    row.username = rs.getString("username");
                    row.recordTimeReceived = rs.getLong("record_time_received");
                    row.recordPayload = rs.getString("record_payload");
                    row.messageJson = rs.getString("message_json");
                    out.add(row);
                }
            }
        }

        return out;
    }
}
