package com.att.tdp.issueflow.auth.jwt;

import com.att.tdp.issueflow.auth.AppUserPrincipal;
import com.att.tdp.issueflow.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-request filter that extracts a Bearer token, verifies it, checks the
 * revocation deny-list, and populates the {@code SecurityContextHolder}
 * with an {@link AppUserPrincipal} when the token is valid.
 *
 * On any failure (no header, malformed, bad signature, expired, revoked)
 * the filter does NOT throw — it simply leaves the security context empty.
 * The downstream {@code AuthorizationFilter} sees an unauthenticated request
 * and returns 401 via {@code SecurityProblemHandlers.entryPoint()}.
 *
 * Extending {@link OncePerRequestFilter} guarantees the filter runs at most
 * once per request even with forward/include dispatching.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final RevokedTokenRepository revokedTokenRepository;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   RevokedTokenRepository revokedTokenRepository) {
        this.jwtService = jwtService;
        this.revokedTokenRepository = revokedTokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String token = extractToken(request);
        if (token != null) {
            try {
                Claims claims = jwtService.parse(token);

                String jti = claims.getId();
                if (jti != null && revokedTokenRepository.existsByJti(jti)) {
                    log.debug("Rejected revoked token jti={}", jti);
                } else {
                    AppUserPrincipal principal = principalFromClaims(claims);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    principal, null, principal.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException ex) {
                // Malformed, bad signature, expired — log at debug and leave
                // the context empty. The entry point will produce 401.
                log.debug("Rejected token: {}", ex.getMessage());
            }
        }

        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    private AppUserPrincipal principalFromClaims(Claims claims) {
        Long userId = claims.get("uid", Long.class);
        String username = claims.getSubject();
        UserRole role = UserRole.valueOf(claims.get("role", String.class));
        return new AppUserPrincipal(userId, username, role);
    }
}