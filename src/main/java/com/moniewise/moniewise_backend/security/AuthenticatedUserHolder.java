package com.moniewise.moniewise_backend.security;

import com.moniewise.moniewise_backend.entity.User;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/**
 * Request-scoped holder that carries the authenticated {@link User} entity
 * through the lifecycle of a single HTTP request.
 *
 * <p>The JWT filter populates this after its single {@code findByEmail} call.
 * Controllers and services can then inject this holder instead of calling
 * {@code userService.findByEmail(email)} again — eliminating one redundant
 * DB query per controller method.
 *
 * <p>The bean is proxied ({@code ScopedProxyMode.TARGET_CLASS}) so it can be
 * safely injected into singleton-scoped beans (controllers, services) without
 * needing to look it up from the {@code ApplicationContext} manually.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class AuthenticatedUserHolder {

    private User user;

    /** Returns the authenticated user for this request, or {@code null} for public endpoints. */
    public User getUser() {
        return user;
    }

    /**
     * Called by {@link JwtAuthenticationFilter} after the user entity is loaded.
     * Must not be called from anywhere else.
     */
    public void setUser(User user) {
        this.user = user;
    }

    /** Convenience: returns true if the holder has been populated (authenticated request). */
    public boolean isPresent() {
        return user != null;
    }
}
