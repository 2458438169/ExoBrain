package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final AuthService auth;
    private final AuthProperties properties;
    private final ObjectMapper json;
    public AuthFilter(AuthService auth, AuthProperties properties, ObjectMapper json) {
        this.auth = auth; this.properties = properties; this.json = json;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawPath = request.getRequestURI();
        String path = request.getServletPath();
        if (path != null && request.getPathInfo() != null) path += request.getPathInfo();
        if (path == null || path.isEmpty()) path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!path.startsWith("/api") && !path.startsWith("/sse/")) { chain.doFilter(request, response); return; }
        try {
            // Servlet path parameters must not turn an excluded public path into an authenticated route.
            if (rawPath.contains(";") || rawPath.contains("\\") || rawPath.contains("%")
                    || path.contains("/../") || path.contains("/./")) throw AuthException.missing();
            if (path.equals("/api/demo") || path.startsWith("/api/demo/") || path.startsWith("/sse/demo/")) {
                chain.doFilter(request, response); return;
            }
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            if (path.equals("/api/auth/login") && "POST".equals(request.getMethod())) {
                requireSameOrigin(request);
                chain.doFilter(request, response); return;
            }
            AuthRepository.LoginSession session = auth.authenticate(cookie(request));
            if (session == null) throw AuthException.unauthorized();
            AuthContext.set(session);
            requireSameOrigin(request);
            if (path.startsWith("/api/admin/") || path.equals("/api/admin")) AuthContext.requireAdmin();
            if (!READ_METHODS.contains(request.getMethod())) {
                if (!AuthTokens.matches(session.csrfToken(), request.getHeader("X-CSRF-Token")))
                    throw new AuthException(403, "请求验证失败，请刷新页面重试");
            }
            chain.doFilter(request, response);
        } catch (AuthException error) {
            response.setStatus(error.getStatus());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            if (error.getStatus() == 429) response.setHeader("Retry-After", "300");
            json.writeValue(response.getOutputStream(), ApiResponse.error(error.getStatus(), error.getMessage()));
        } finally { AuthContext.clear(); }
    }
    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        String token = null;
        for (Cookie cookie : request.getCookies()) {
            if (!properties.cookieName().equals(cookie.getName())) continue;
            if (token != null) return null; // reject duplicate names rather than guessing which cookie is authoritative
            token = cookie.getValue();
        }
        return token;
    }
    void requireSameOrigin(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) throw AuthException.forbidden();
        String configured = properties.getPublicOrigin();
        String expected = configured != null && !configured.isBlank() ? origin(configured)
                : origin(request.getRequestURL().toString());
        String supplied = request.getHeader("Origin");
        if (supplied == null) supplied = request.getHeader("Referer");
        // Non-browser API clients may omit Origin but still need the session's CSRF token for mutations.
        if (supplied != null && !expected.equals(origin(supplied))) throw AuthException.forbidden();
    }
    private String origin(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getHost() == null ||
                    !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                throw AuthException.forbidden();
            int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException error) { throw AuthException.forbidden(); }
    }
}
