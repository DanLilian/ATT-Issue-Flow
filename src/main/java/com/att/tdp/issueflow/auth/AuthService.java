package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.auth.dto.LoginRequest;
import com.att.tdp.issueflow.auth.dto.LoginResponse;
import com.att.tdp.issueflow.auth.dto.MeResponse;
import com.att.tdp.issueflow.auth.jwt.JwtService;
import com.att.tdp.issueflow.auth.jwt.RevokedToken;
import com.att.tdp.issueflow.auth.jwt.RevokedTokenRepository;
import com.att.tdp.issueflow.common.error.NotFoundException;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Authentication operations: login (issue token), logout (revoke token),
 * and current-user lookup.
 *
 * Password verification is delegated to Spring's AuthenticationManager,
 * which uses the auto-wired DaoAuthenticationProvider + AppUserDetailsService
 * + PasswordEncoder chain. We never call passwordEncoder.matches directly —
 * the framework's path handles timing-safe comparison correctly.
 *
 * Login failures (bad password OR missing user) are normalized to a single
 * BadCredentialsException at the controller boundary so callers cannot
 * enumerate valid usernames.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RevokedTokenRepository revokedTokenRepository;
    private final UserRepository userRepository;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       RevokedTokenRepository revokedTokenRepository,
                       UserRepository userRepository) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.revokedTokenRepository = revokedTokenRepository;
        this.userRepository = userRepository;
    }

    /**
     * Verifies credentials via the framework and issues a fresh JWT.
     * Throws BadCredentialsException for either wrong password OR
     * unknown username (caller maps to 401, masking the distinction).
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        try {
            // Framework call: throws if username unknown or password wrong.
            // The returned Authentication carries the principal, which we
            // ignore — we re-fetch the User entity to issue a token with
            // the uid claim populated from the DB.
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (AuthenticationException ex) {
            // Normalize ALL authentication failures (bad password, missing
            // user, disabled account, etc.) to a single exception type so
            // callers cannot distinguish between them.
            throw new BadCredentialsException("Invalid username or password");
        }

        // Credentials verified. Fetch the user to populate token claims.
        User user = userRepository.findByUsernameIgnoreCase(req.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        JwtService.IssuedToken issued = jwtService.issueFor(user);

        // TODO Phase 9: auditService.record(LOGIN, USER, user.getId(), ...);

        return LoginResponse.bearer(issued.token(), issued.expiresInSeconds());
    }

    /**
     * Adds the supplied token's jti to the deny-list. Idempotent: if the
     * jti is already present, this is a no-op (the contract is "after this
     * returns, the token does not work" and that is already true).
     *
     * The token has already been validated by JwtAuthenticationFilter
     * before this method runs, so signature verification is not required
     * here. We do recover the jti via extractJti, which tolerates expired
     * tokens defensively.
     */
    @Transactional
    public void logout(String token) {
        String jti = jwtService.extractJti(token);
        if (jti == null) {
            // Token had no jti claim — nothing to revoke. Shouldn't happen
            // since we always issue with a jti, but defensive.
            return;
        }
        if (revokedTokenRepository.existsByJti(jti)) {
            return; // already revoked, idempotent
        }

        // The token's expiry tells us how long the deny-list entry must
        // persist. Past expiry, the signature check rejects the token
        // anyway, so the deny-list entry becomes redundant.
        Instant expiry = jwtService.parse(token).getExpiration().toInstant();
        revokedTokenRepository.save(new RevokedToken(jti, expiry));

        // TODO Phase 9: auditService.record(LOGOUT, USER, currentUserId, ...);
    }

    /**
     * Returns the profile of the authenticated user. The principal in the
     * security context carries the user id from the JWT claim, so we
     * fetch by id rather than re-resolving username -> user.
     */
    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> NotFoundException.of("User", userId));
        return MeResponse.from(user);
    }
}