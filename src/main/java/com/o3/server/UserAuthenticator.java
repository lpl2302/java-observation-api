package com.o3.server;

import com.sun.net.httpserver.BasicAuthenticator;

public class UserAuthenticator extends BasicAuthenticator {

    // auth delegates to sqlite-backed credential validation.
    private final MessageDatabase db;

    public UserAuthenticator(MessageDatabase db) {
        // the HTTP auth realm shown to clients.
        super("secure");
        this.db = db;
    }

    @Override
    public boolean checkCredentials(String username, String password) {
        try {
            // accept only when hash comparison passes in db layer.
            return db.validateCredentials(username, password);
        } catch (Exception e) {
            // fail closed if db has any issue.
            return false;
        }
    }
}
