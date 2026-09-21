package com.moniewise.moniewise_backend.security;

import com.moniewise.moniewise_backend.entity.User; // Import your User entity
import com.moniewise.moniewise_backend.service.AuthSessionService;
import com.moniewise.moniewise_backend.service.UserService;
import io.jsonwebtoken.ExpiredJwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final AuthSessionService authSessionService;
    private final AuthenticatedUserHolder userHolder;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   UserService userService,
                                   AuthSessionService authSessionService,
                                   AuthenticatedUserHolder userHolder) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
        this.authSessionService = authSessionService;
        this.userHolder = userHolder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        String token = null;
        String email = null;

        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
            try {
                email = jwtUtil.extractEmail(token);
            } catch (ExpiredJwtException e) {
                log.warn("JWT expired for request: {}", request.getRequestURI());
            } catch (Exception e) {
                log.warn("Invalid JWT: {}", e.getMessage());
            }
        }

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            // 1. Single DB call: findByEmail checks isDeleted() and returns the full entity.
            //    buildUserDetails converts it to Spring's UserDetails in-memory — no second query.
            User user = userService.findByEmail(email);
            UserDetails userDetails = userService.buildUserDetails(user);

            // Store the entity in the request-scoped holder so controllers can
            // access the User object without issuing another DB query.
            userHolder.setUser(user);

            // 2. Extract Session ID from the incoming Token
            String tokenSessionId = jwtUtil.extractSessionId(token);

            boolean isSessionValid = tokenSessionId != null &&
                    authSessionService.isSessionActive(email, tokenSessionId);

            if (jwtUtil.validateToken(token, userDetails) && isSessionValid) {
                String scope = jwtUtil.extractScope(token);
                if (scope != null) {
                    request.setAttribute("jwt.scope", scope);
                }
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("Set authentication for: {}", email);
            } else if (!isSessionValid) {
                log.warn("Session Mismatch: User {} tried to use an old token/device.", email);
            }
        }

        chain.doFilter(request, response);
    }
}
