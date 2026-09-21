package com.moniewise.moniewise_backend.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Enforces JWT scope restrictions after authentication.
 *
 * A token with {@code scope=blog} can ONLY access {@code /admin/blog/**}.
 * Any attempt to use it on other endpoints gets a 403.
 * This prevents a blog admin token from accessing wallets, budgets, user data, etc.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class BlogScopeFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String scope = (String) request.getAttribute("jwt.scope");
        String path = request.getRequestURI();

        if ("blog".equals(scope) && !path.startsWith("/admin/blog")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"This token is scoped to blog administration only\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
