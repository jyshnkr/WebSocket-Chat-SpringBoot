package com.ADPFProject.SpringBoot.WebSocketChatApplication.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Service;

/**
 * Creates user accounts. Passwords are stored only as BCrypt hashes.
 */
@Service
public class UserAccountService {

    private final UserDetailsManager users;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserDetailsManager users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** Registers a regular user (role USER). Usernames are case-insensitive. */
    public synchronized void register(String username, String rawPassword) {
        if (users.userExists(username)) {
            throw new UsernameAlreadyExistsException(username);
        }
        users.createUser(User.withUsername(username)
                .password(passwordEncoder.encode(rawPassword))
                .roles("USER")
                .build());
    }
}
