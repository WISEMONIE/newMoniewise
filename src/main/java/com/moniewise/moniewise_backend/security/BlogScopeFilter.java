package com.moniewise.moniewise_backend.security;

import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

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
        // The website admin token: blog, payment reviews and signing out. Nothing else.
        if (JwtUtil.SITE_ADMIN_SCOPE.equals(scope) && !siteAdminPath(path)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"This token is scoped to the website admin only\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    static boolean siteAdminPath(String path) {
        return path.startsWith("/admin/blog/") || path.equals("/admin/blog")
                || path.startsWith("/admin/aml/") || path.equals("/admin/aml")
                || path.startsWith("/admin/notifications/") || path.equals("/admin/notifications")
                || path.startsWith("/admin/budgets/") || path.equals("/admin/budgets")
                || path.equals("/site-admin/auth/logout");
    }
}
