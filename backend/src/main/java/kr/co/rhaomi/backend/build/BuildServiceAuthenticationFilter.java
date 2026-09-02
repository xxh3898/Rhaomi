package kr.co.rhaomi.backend.build;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BuildServiceAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final List<SimpleGrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_BUILD_SERVICE"));

    private final BuildServiceProperties properties;

    public BuildServiceAuthenticationFilter(BuildServiceProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isConfigured()) {
            BuildSecurityResponses.unavailable(response);
            return;
        }

        var authorizationHeaders = Collections.list(request.getHeaders("Authorization"));
        if (authorizationHeaders.size() != 1) {
            BuildSecurityResponses.unauthorized(response);
            return;
        }

        var authorization = authorizationHeaders.getFirst();
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            BuildSecurityResponses.unauthorized(response);
            return;
        }

        var candidate = authorization.substring(BEARER_PREFIX.length());
        if (!properties.matches(candidate)) {
            BuildSecurityResponses.unauthorized(response);
            return;
        }

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                BuildServicePrincipal.INSTANCE, null, AUTHORITIES));
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }
}
