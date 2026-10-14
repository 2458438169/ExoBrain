package com.kama.jchatmind.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String HASH = "a".repeat(64);
    private final AuthRepository repository = mock(AuthRepository.class);
    private final PasswordHasher passwords = mock(PasswordHasher.class);
    private final AuthUser admin = new AuthUser(ID, "admin", "管理员", "ADMIN", true, Instant.now());
    private AuthService service;
    @BeforeEach void setup() {
        when(passwords.hash(any())).thenReturn("hashed-password");
        service = new AuthService(repository, passwords, new AuthProperties(), new LoginRateLimiter());
        AuthContext.set(new AuthRepository.LoginSession(admin, "hash", "csrf", Instant.now().plusSeconds(3600), 2));
    }
    @AfterEach void cleanup() { AuthContext.clear(); }
    @Test void successfulLoginStoresOnlyTheTokenDigest() {
        when(repository.findByUsernameForUpdate("admin")).thenReturn(new AuthRepository.StoredUser(admin, "encoded", 2));
        when(passwords.verify("correct-password", "encoded")).thenReturn(true);
        var result = service.login("ADMIN", "correct-password", "peer");
        assertEquals(43, result.token().length());
        assertEquals(43, result.csrfToken().length());
        assertNotEquals(result.token(), result.csrfToken());
        assertTrue(result.expiresAt().isAfter(Instant.now().plusSeconds(43_190)));
        verify(repository).insertSession(ID, AuthTokens.hash(result.token()), result.csrfToken(), result.expiresAt(), 2);
        verify(repository, never()).insertSession(any(), eq(result.token()), any(), any(), anyInt());
    }
    @Test void missingDisabledAndWrongPasswordUseTheSameLoginError() {
        String missing = assertThrows(AuthException.class, () -> service.login("missing", "wrong", "peer")).getMessage();
        var disabled = new AuthUser(ID, "admin", "管理员", "ADMIN", false, admin.createdAt());
        when(repository.findByUsernameForUpdate("admin")).thenReturn(new AuthRepository.StoredUser(disabled, "encoded", 2));
        when(passwords.verify("correct", "encoded")).thenReturn(true);
        assertEquals(missing, assertThrows(AuthException.class, () -> service.login("admin", "correct", "peer")).getMessage());
        when(repository.findByUsernameForUpdate("admin")).thenReturn(new AuthRepository.StoredUser(admin, "encoded", 2));
        assertEquals(missing, assertThrows(AuthException.class, () -> service.login("admin", "wrong", "peer")).getMessage());
        verify(repository, never()).insertSession(any(), any(), any(), any(), anyInt());
    }
    @Test void theFinalAdministratorCannotBeDisabledOrDemoted() {
        when(repository.findByIdForUpdate(ID)).thenReturn(new AuthRepository.StoredUser(admin, "encoded", 2));
        when(repository.countEnabledAdmins()).thenReturn(1L);
        assertEquals(409, assertThrows(AuthException.class, () -> service.update(ID,
                new AuthService.UpdateUser(null, "USER", null, null))).getStatus());
        assertEquals(409, assertThrows(AuthException.class, () -> service.update(ID,
                new AuthService.UpdateUser(null, null, false, null))).getStatus());
        verify(repository, never()).updateUser(any(), any());
    }
    @Test void resettingPasswordDoesNotReturnThePasswordAndChangesTheStoredHash() {
        when(repository.findByIdForUpdate(ID)).thenReturn(new AuthRepository.StoredUser(admin, "oldhash", 2));
        AuthUser result = service.update(ID, new AuthService.UpdateUser(null, null, null, "newpass123"));
        assertEquals(admin, result);
        verify(repository).updateUser(admin, "hashed-password");
        assertFalse(result.toString().contains("newpass123"));
    }
    @Test void passwordChangeRequiresTheCurrentPassword() {
        when(repository.findByIdForUpdate(ID)).thenReturn(new AuthRepository.StoredUser(admin, "encoded", 2));
        assertEquals(400, assertThrows(AuthException.class, () -> service.changePassword("wrong", "newpass123")).getStatus());
        verify(repository, never()).updateUser(any(), any());
        when(passwords.verify("correct", "encoded")).thenReturn(true);
        service.changePassword("correct", "newpass123");
        verify(repository).updateUser(admin, "hashed-password");
    }
    @Test void bootstrapNeverOverwritesExistingUsers() {
        when(repository.countUsers()).thenReturn(1L);
        assertFalse(service.bootstrap("admin", "initialpass"));
        verify(repository, never()).insertUser(any(), any());
    }
    @Test void ordinaryUsersCannotCreateOrListAccounts() {
        AuthContext.set(new AuthRepository.LoginSession(new AuthUser(ID, "demo", "体验", "USER", true, Instant.now()),
                "hash", "csrf", Instant.now().plusSeconds(100), 2));
        assertEquals(403, assertThrows(AuthException.class, service::users).getStatus());
        assertEquals(403, assertThrows(AuthException.class, () -> service.create(new AuthService.CreateUser(
                "hacker", "hacker", "pass1234", "ADMIN", true))).getStatus());
        verifyNoInteractions(repository);
    }
    @Test void queuedWorkCannotUseAnOldAuthVersion() {
        when(repository.findSession(HASH)).thenReturn(new AuthRepository.LoginSession(admin, HASH, "csrf", Instant.now().plusSeconds(300), 3));
        assertEquals(401, assertThrows(AuthException.class, () -> service.currentActor(ID, 2, HASH)).getStatus());
        assertEquals(admin, service.currentActor(ID, 3, HASH));
    }
    @Test void runtimeIdentityMustMatchTheSpecificInitiatingSession() {
        when(repository.findSession(HASH)).thenReturn(new AuthRepository.LoginSession(admin, HASH, "csrf", Instant.now().plusSeconds(300), 2));
        String anotherUser = "22222222-2222-4222-8222-222222222222";
        assertEquals(401, assertThrows(AuthException.class, () -> service.currentActor(anotherUser, 2, HASH)).getStatus());
        assertEquals(401, assertThrows(AuthException.class, () -> service.currentActor(ID, 2, null)).getStatus());
        assertEquals(401, assertThrows(AuthException.class, () -> service.currentActor(ID, 2, "raw-token")).getStatus());
        when(repository.findSession(HASH)).thenReturn(null);
        assertEquals(401, assertThrows(AuthException.class, () -> service.currentActor(ID, 2, HASH)).getStatus());
    }
}
