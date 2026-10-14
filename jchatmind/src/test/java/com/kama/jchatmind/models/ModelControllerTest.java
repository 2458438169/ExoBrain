package com.kama.jchatmind.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelControllerTest {
    private final ModelCatalogService catalog = mock(ModelCatalogService.class);
    private final ModelRuntimeService runtime = mock(ModelRuntimeService.class);
    private final AuthService auth = mock(AuthService.class);
    private final AuthProperties properties = new AuthProperties();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(auth.authenticate("user-session")).thenReturn(session("USER"));
        when(auth.authenticate("admin-session")).thenReturn(session("ADMIN"));
        mvc = MockMvcBuilders.standaloneSetup(new ModelController(catalog, runtime))
                .addFilters(new AuthFilter(auth, properties, new ObjectMapper()))
                .setControllerAdvice(new AuthExceptionHandler(), new ModelExceptionHandler()).build();
    }

    @Test
    void usersOnlyGetTheSafeCatalogAndGuestsNeedLogin() throws Exception {
        when(catalog.enabledModels()).thenReturn(List.of(new ModelViews.UserModel("deepseek-chat", "DeepSeek",
                ModelKind.LLM, "deepseek-chat", true, true, null)));
        mvc.perform(get("/api/models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/models").cookie(cookie("user-session")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.models[0].id").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.models[0].baseUrl").doesNotExist())
                .andExpect(jsonPath("$.data.models[0].apiKey").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PATCH", "DELETE", "TEST"})
    void ordinaryUserCannotReadModifyDeleteOrProbeAdminModels(String action) throws Exception {
        var request = switch (action) {
            case "POST" -> post("/api/admin/models").content("{}").contentType(MediaType.APPLICATION_JSON);
            case "PATCH" -> patch("/api/admin/models/test").content("{}").contentType(MediaType.APPLICATION_JSON);
            case "DELETE" -> delete("/api/admin/models/test");
            case "TEST" -> post("/api/admin/models/test/test");
            default -> get("/api/admin/models");
        };
        mvc.perform(request.cookie(cookie("user-session")).header("X-CSRF-Token", "test-csrf"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog, runtime);
    }

    @Test
    void controllerAlsoRequiresAdminWhenInvokedWithoutGatewayOrAuthFilter() throws Exception {
        MockMvc direct = MockMvcBuilders.standaloneSetup(new ModelController(catalog, runtime))
                .setControllerAdvice(new AuthExceptionHandler()).build();
        direct.perform(get("/api/admin/models")).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog, runtime);
    }

    @Test
    void administratorMustProvideCsrfTokenForMutations() throws Exception {
        mvc.perform(delete("/api/admin/models/test").cookie(cookie("admin-session")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/models/test").cookie(cookie("admin-session"))
                        .header("X-CSRF-Token", "test-csrf"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(catalog).delete("test");
    }

    private Cookie cookie(String value) { return new Cookie(properties.cookieName(), value); }

    private AuthRepository.LoginSession session(String role) {
        return new AuthRepository.LoginSession(new AuthUser("11111111-1111-4111-8111-111111111111", "test", "Test",
                role, true, Instant.now()), "test-hash", "test-csrf", Instant.now().plusSeconds(3600), 1);
    }
}
