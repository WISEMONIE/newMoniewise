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

        chain.doFilter(request, response);
    }
}
