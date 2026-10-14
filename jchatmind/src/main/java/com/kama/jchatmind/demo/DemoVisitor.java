package com.kama.jchatmind.demo;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class DemoVisitor {
    static final String COOKIE_NAME = "exobrain_demo";
    private final SecureRandom random = new SecureRandom();

    public String owner(HttpServletRequest request, HttpServletResponse response) {
        String token = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue().matches("[A-Za-z0-9_-]{43}")) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        if (token == null) {
            byte[] entropy = new byte[32];
            random.nextBytes(entropy);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, token)
                    .httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/")
                    .maxAge(Duration.ofDays(30)).build().toString());
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return hash(token);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
