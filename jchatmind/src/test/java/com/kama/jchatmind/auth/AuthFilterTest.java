package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.common.ApiResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.net.URI;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthFilterTest {
    private final AuthService auth = mock(AuthService.class);
    private final AuthProperties properties = new AuthProperties();
    private final String token = "A".repeat(43);
    private final String csrf = "B".repeat(43);
    private final AuthUser user = new AuthUser("11111111-1111-4111-8111-111111111111", "demo", "体验用户", "USER", true, Instant.now());
    private MockMvc mvc;
    private Cookie cookie;

    @BeforeEach void setup() {
        properties.setPublicOrigin("https://example.test");
        cookie = new Cookie(properties.cookieName(), token);
        when(auth.authenticate(token)).thenReturn(session(user));
        mvc = MockMvcBuilders.standaloneSetup(new ProtectedController(), new AuthController(auth, properties))
                .addFilters(new AuthFilter(auth, properties, new ObjectMapper()))
                .setControllerAdvice(new AuthExceptionHandler()).build();
    }
    @Test void everyPrivateApiAndSseRequireTheSessionNotBasicCredentials() throws Exception {
        for (String path : new String[]{"/api/agents", "/api/admin/users", "/api/auth/me", "/sse/connect/id"}) {
            mvc.perform(get(path).header("Authorization", "Basic YWRtaW46YWRtaW4="))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        }
        mvc.perform(get("/api/agents").cookie(new Cookie(properties.cookieName(), "expired"))).andExpect(status().isUnauthorized());
        assertThrows(AuthException.class, AuthContext::requireUser);
    }
    @Test void usersCannotEnterTheAdministratorNamespace() throws Exception {
        mvc.perform(get("/api/admin/users").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/agents").cookie(cookie)).andExpect(status().isOk());
        assertThrows(AuthException.class, AuthContext::requireUser);
        when(auth.authenticate(token)).thenReturn(session(new AuthUser(user.id(), "admin", "Admin", "ADMIN", true, user.createdAt())));
        mvc.perform(get("/api/admin/users").cookie(cookie)).andExpect(status().isOk());
    }
    @Test void writesNeedCsrfAndTheExactPublicOrigin() throws Exception {
        mvc.perform(post("/api/agents").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/agents").cookie(cookie).header("X-CSRF-Token", "wrong")).andExpect(status().isForbidden());
        mvc.perform(post("/api/agents").cookie(cookie).header("X-CSRF-Token", csrf).header("Origin", "https://attacker.test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agents").cookie(cookie).header("X-CSRF-Token", csrf).header("Origin", "null"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agents").cookie(cookie).header("X-CSRF-Token", csrf).header("Referer", "https://attacker.test/path"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agents").cookie(cookie).header("X-CSRF-Token", csrf).header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agents").cookie(cookie).header("X-CSRF-Token", csrf).header("Origin", "https://example.test"))
                .andExpect(status().isOk());
        // CLI clients do not have Origin; they still possess the explicit CSRF secret.
        mvc.perform(post("/api/agents").cookie(cookie).header("X-CSRF-Token", csrf)).andExpect(status().isOk());
    }
    @Test void loginUsesAHardenedCookieAndNeverReturnsTheSessionToken() throws Exception {
        when(auth.login(eq("demo"), eq("demo1234"), anyString())).thenReturn(new AuthService.LoginResult(
                user, token, csrf, Instant.now().plus(12, ChronoUnit.HOURS)));
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"demo\",\"password\":\"demo1234\"}")
                        .header("Origin", "https://example.test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Set-Cookie", allOf(containsString("__Host-exobrain_session="), containsString("Path=/"),
                        containsString("HttpOnly"), containsString("Secure"), containsString("SameSite=Lax"))))
                .andExpect(jsonPath("$.data.user.role").value("USER")).andExpect(jsonPath("$.data.csrfToken").value(csrf))
                .andExpect(content().string(not(containsString(token)))).andExpect(content().string(not(containsString("demo1234"))));
    }
    @Test void loginCannotBeSubmittedFromAnotherOrigin() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"demo\",\"password\":\"demo1234\"}")
                        .header("Origin", "https://attacker.test"))
                .andExpect(status().isForbidden());
        verify(auth, never()).login(any(), any(), any());
    }
    @Test void logoutAndPasswordChangeClearTheCookie() throws Exception {
        mvc.perform(post("/api/auth/logout").cookie(cookie).header("X-CSRF-Token", csrf))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        verify(auth).logout();
        mvc.perform(patch("/api/auth/password").cookie(cookie).header("X-CSRF-Token", csrf).contentType("application/json")
                        .content("{\"currentPassword\":\"demo1234\",\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        verify(auth).changePassword("demo1234", "newpass123");
    }
    @Test void publicDemoAndHealthRemainUnauthenticated() throws Exception {
        mvc.perform(get("/api/demo/agents")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/api/demo;admin/agents")).andExpect(status().isNotFound());
        mvc.perform(get(URI.create("/%61pi/agents"))).andExpect(status().isNotFound());
        mvc.perform(get(URI.create("/api/demo/%2e%2e/agents"))).andExpect(status().isNotFound());
    }
    @Test void duplicateSessionCookiesFailClosed() throws Exception {
        mvc.perform(get("/api/agents").cookie(cookie, cookie)).andExpect(status().isUnauthorized());
    }
    private AuthRepository.LoginSession session(AuthUser actor) {
        return new AuthRepository.LoginSession(actor, AuthTokens.hash(token), csrf, Instant.now().plusSeconds(3600), 1);
    }
    @RestController static class ProtectedController {
        @GetMapping({"/api/agents", "/api/admin/users", "/api/demo/agents", "/health", "/sse/connect/id"})
        Object get() { return ApiResponse.success(Map.of("ok", true)); }
        @PostMapping("/api/agents") Object post() { return ApiResponse.success(); }
    }
}
