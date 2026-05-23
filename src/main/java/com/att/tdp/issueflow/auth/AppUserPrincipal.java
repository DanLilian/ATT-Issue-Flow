package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Authenticated caller's identity. Stored in the {@code SecurityContext}
 * by the JWT filter on protected requests, and by {@code AuthService} during
 * password-based login.
 *
 * Controllers obtain it via {@code @AuthenticationPrincipal AppUserPrincipal}.
 * The principal carries the user ID and role directly so authenticated
 * requests do not require a DB lookup to identify the caller.
 *
 * {@code password} is null after the credentials have been verified — the
 * principal is built post-authentication and never carries the hash.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long userId;
    private final String username;
    private final UserRole role;

    public AppUserPrincipal(Long userId, String username, UserRole role) {
        this.userId = userId;
        this.username = username;
        this.role = role;
    }

    public static AppUserPrincipal from(User user) {
        return new AppUserPrincipal(user.getId(), user.getUsername(), user.getRole());
    }

    public Long getUserId() { return userId; }
    public UserRole getRole() { return role; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // The ROLE_ prefix is Spring Security convention: hasRole('ADMIN')
        // looks for an authority literally named ROLE_ADMIN. Adding the
        // prefix here keeps controller annotations idiomatic.
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return null; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return true; }
}