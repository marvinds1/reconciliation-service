package io.github.marvinds1.reconciliation.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API key authentication for {@code /api/**} plus defensive headers for financial data.
 * Keys come from {@code reconciliation.api-keys} (comma-separated, several allowed for rotation)
 * and are compared in constant time. With no keys configured, authentication is off and a
 * warning is logged at startup.
 */
@Component
public class ApiSecurityFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    private static final int MIN_KEY_LENGTH = 16;
    private static final Logger log = LoggerFactory.getLogger(ApiSecurityFilter.class);

    private final List<byte[]> keys;

    public ApiSecurityFilter(@Value("${reconciliation.api-keys:}") String configuredKeys) {
        this.keys = Arrays.stream(configuredKeys.split(","))
                .map(String::strip)
                .filter(k -> !k.isEmpty())
                .peek(k -> {
                    if (k.length() < MIN_KEY_LENGTH) {
                        throw new IllegalArgumentException("API keys must be at least " + MIN_KEY_LENGTH + " characters");
                    }
                })
                .map(k -> k.getBytes(StandardCharsets.UTF_8))
                .toList();
        if (keys.isEmpty()) {
            log.warn("reconciliation.api-keys is empty: API authentication is DISABLED");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!keys.isEmpty() && !valid(request.getHeader(HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Unauthorized","status":401,\
                    "detail":"missing or invalid API key","code":"UNAUTHORIZED"}""");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean valid(String candidate) {
        if (candidate == null) {
            return false;
        }
        byte[] supplied = candidate.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (byte[] key : keys) {
            matched |= MessageDigest.isEqual(supplied, key);
        }
        return matched;
    }
}
