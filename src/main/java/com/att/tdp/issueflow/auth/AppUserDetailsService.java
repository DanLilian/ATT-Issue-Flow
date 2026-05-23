package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads users for Spring Security's authentication mechanism.
 * Called by {@code AuthService} during password-based login (Phase 5c)
 * and exposed as a bean so future password-grant flows can resolve users
 * by username.
 *
 * Returns a {@link UserDetails} that carries the BCrypt hash so the
 * authentication manager can verify the supplied password. After login,
 * the principal stored in the security context is a stripped-down
 * {@link AppUserPrincipal} without the hash.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found: " + username));

        // org.springframework.security.core.userdetails.User carries the
        // password hash — needed for credential verification. This is
        // intentionally NOT AppUserPrincipal, which is built post-auth.
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .authorities("ROLE_" + user.getRole().name())
                .build();
    }
}