package com.ktb.chatapp.security;

import com.ktb.chatapp.model.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authentication principal that keeps the already loaded domain user.
 * This lets the login flow reuse the authentication lookup result instead of
 * querying MongoDB again for the response and session owner.
 */
public record AuthenticatedUser(User user) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }
}
