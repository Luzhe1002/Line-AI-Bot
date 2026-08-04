package com.lineaibot.shared;

import com.lineaibot.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; "
            + "script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'; "
            + "form-action 'self'";

    private final AppProperties properties;

    public SecurityHeadersFilter(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(
                "Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        if ("production".equalsIgnoreCase(properties.getEnvironment())) {
            response.setHeader(
                    "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        if (isSensitiveApi(request.getRequestURI())) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }

        filterChain.doFilter(request, response);
    }

    private boolean isSensitiveApi(String path) {
        return path.startsWith("/api/")
                || path.contains("/api/")
                || path.startsWith("/webhooks/line");
    }
}
