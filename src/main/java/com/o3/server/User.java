package com.o3.server;

public class User {
    // store user identity fields as immutable values.
    private final String username;
    private final String password;
    private final String email;
    private final String nickname;

    public User(String username, String password, String email, String nickname) {
        // keep constructor simple: caller validates the payload.
        this.username = username;
        this.password = password;
        this.email = email;
        this.nickname = nickname;
    }

    // plain getters used by db insert/auth logic.
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getEmail() { return email; }
    public String getNickname() { return nickname; }
}
