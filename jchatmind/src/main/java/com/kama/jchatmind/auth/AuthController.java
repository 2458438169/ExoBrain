package com.kama.jchatmind.auth;

import com.kama.jchatmind.model.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(String username, String password) { }
    public record PasswordRequest(String currentPassword, String newPassword) { }
    public record Identity(AuthUser user, String csrfToken) { }
    private final AuthService auth;
    private final AuthProperties properties;
    public AuthController(AuthService auth, AuthProperties properties) { this.auth = auth; this.properties = properties; }
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Identity> login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        var result = auth.login(input.username(), input.password(), request.getRemoteAddr());
        setCookie(response, result.token(), Math.max(0, Duration.between(Instant.now(), result.expiresAt()).getSeconds()));
        return ApiResponse.success(new Identity(result.user(), result.csrfToken()));
    }
    @GetMapping("/me")
    public ApiResponse<Identity> me() {
        var session = AuthContext.requireSession();
        return ApiResponse.success(new Identity(session.user(), session.csrfToken()));
    }
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        auth.logout(); setCookie(response, "", 0); return ApiResponse.success();
    }
    @PatchMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> password(@RequestBody PasswordRequest input, HttpServletResponse response) {
        auth.changePassword(input.currentPassword(), input.newPassword());
        setCookie(response, "", 0); return ApiResponse.success();
    }
    private void setCookie(HttpServletResponse response, String value, long seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true).secure(properties.isSecureCookie()).sameSite("Lax").path("/").maxAge(seconds).build().toString());
        response.setHeader("Cache-Control", "no-store");
    }
}
