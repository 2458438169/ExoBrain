package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoProperties;
import com.kama.jchatmind.mapper.*;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.*;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.models.ModelCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceAccessTest {
    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String OWN = "22222222-2222-4222-8222-222222222222";
    private static final String OTHER = "33333333-3333-4333-8333-333333333333";
    private static final String PUBLIC = "44444444-4444-4444-8444-444444444444";
    private static final String SESSION = "55555555-5555-4555-8555-555555555555";
    private static final String HASH = "a".repeat(64);
    private final ResourceAclRepository acl = mock(ResourceAclRepository.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final KnowledgeBaseMapper kbs = mock(KnowledgeBaseMapper.class);
    private final DocumentMapper docs = mock(DocumentMapper.class);
    private final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final AuthService auth = mock(AuthService.class);
    private final ModelCatalogService models = mock(ModelCatalogService.class);
    private final DemoProperties publication = new DemoProperties();
    private final AuthUser user = new AuthUser(USER, "demo", "体验", "USER", true, Instant.now());
    private ResourceAccess access;

    @BeforeEach void setup() {
        publication.setAgentId(PUBLIC); publication.setPublicKbIds(List.of(PUBLIC));
        access = new ResourceAccess(acl, new DemoAccessPolicy(publication), publication, agents,
                new AgentConverter(new ObjectMapper()), kbs, docs, sessions, messages, auth, models);
        AuthContext.set(new AuthRepository.LoginSession(user, "hash", "csrf", Instant.now().plusSeconds(600), 1));
        when(acl.owns(USER, ResourceAccess.AGENT, OWN)).thenReturn(true);
        when(acl.owns(USER, ResourceAccess.KB, OWN)).thenReturn(true);
        when(acl.owns(USER, ResourceAccess.SESSION, SESSION)).thenReturn(true);
        when(kbs.selectById(OWN)).thenReturn(KnowledgeBase.builder().id(OWN).build());
        when(kbs.selectById(PUBLIC)).thenReturn(KnowledgeBase.builder().id(PUBLIC).build());
        when(sessions.selectById(SESSION)).thenReturn(ChatSession.builder().id(SESSION).agentId(OWN).build());
        when(auth.currentActor(USER, 1, HASH)).thenReturn(user);
    }
    @AfterEach void cleanup() { AuthContext.clear(); }
    @Test void usersSeeTheirResourcesAndPublishedCatalogButNeverOtherUsersResources() {
        assertTrue(access.canRead(ResourceAccess.KB, OWN));
        assertTrue(access.canWrite(ResourceAccess.KB, OWN));
        assertTrue(access.canRead(ResourceAccess.KB, PUBLIC));
        assertFalse(access.canWrite(ResourceAccess.KB, PUBLIC));
        assertTrue(access.canRead(ResourceAccess.AGENT, PUBLIC));
        assertFalse(access.canRead(ResourceAccess.KB, OTHER));
        assertFalse(access.canRead(ResourceAccess.AGENT, OTHER));
        assertFalse(access.canRead(ResourceAccess.SESSION, PUBLIC));
        assertEquals(404, assertThrows(AuthException.class, () -> access.requireKb(OTHER, false)).getStatus());
        assertEquals(403, assertThrows(AuthException.class, () -> access.requireKb(PUBLIC, true)).getStatus());
        verify(kbs, never()).selectById(OTHER);
    }
    @Test void administratorCanAccessUnownedHistoricalResources() {
        AuthContext.set(new AuthRepository.LoginSession(new AuthUser(USER, "admin", "Admin", "ADMIN", true, Instant.now()),
                "hash", "csrf", Instant.now().plusSeconds(600), 1));
        assertTrue(access.canRead(ResourceAccess.KB, OTHER));
        assertTrue(access.canWrite(ResourceAccess.AGENT, OTHER));
        assertTrue(access.canRead(ResourceAccess.SESSION, PUBLIC));
        verifyNoInteractions(acl);
    }
    @Test void documentReadAndWriteFollowTheActualKnowledgeBase() {
        when(docs.selectById(OTHER)).thenReturn(Document.builder().id(OTHER).kbId(OTHER).build());
        when(docs.selectById(PUBLIC)).thenReturn(Document.builder().id(PUBLIC).kbId(PUBLIC).build());
        assertEquals(404, assertThrows(AuthException.class, () -> access.requireDocument(OTHER, false)).getStatus());
        assertNotNull(access.requireDocument(PUBLIC, false));
        assertEquals(403, assertThrows(AuthException.class, () -> access.requireDocument(PUBLIC, true)).getStatus());
    }
    @Test void crossUserSessionsAndMessageIdsCannotBeUsedForReadsWritesOrSse() {
        assertEquals(404, assertThrows(AuthException.class, () -> access.requireSession(OTHER)).getStatus());
        when(messages.selectById(OTHER)).thenReturn(ChatMessage.builder().id(OTHER).sessionId(OTHER).role("user").build());
        assertEquals(404, assertThrows(AuthException.class, () -> access.requireMessage(OTHER, true)).getStatus());
        assertEquals(404, assertThrows(AuthException.class, () -> access.requireMessage(OTHER, false)).getStatus());
        verify(sessions, never()).selectById(OTHER);
    }
    @Test void usersCannotAddAdministrativeToolsOrSomeoneElsesKnowledgeBase() {
        AgentDTO config = config();
        config.setAllowedTools(List.of("dataBaseTool"));
        assertEquals(403, assertThrows(AuthException.class, () -> access.validateAgentConfiguration(config)).getStatus());
        config.setAllowedTools(List.of("emailTool"));
        assertEquals(403, assertThrows(AuthException.class, () -> access.validateAgentConfiguration(config)).getStatus());
        config.setAllowedTools(List.of()); config.setAllowedKbs(List.of(OTHER));
        assertEquals(404, assertThrows(AuthException.class, () -> access.validateAgentConfiguration(config)).getStatus());
        config.setAllowedKbs(List.of(OWN, PUBLIC));
        access.validateAgentConfiguration(config);
        verify(models).validateLlmSelection("provided-model");
    }
    @Test void messageSubmissionRejectsAgentMismatchAndForgedRolesOrMetadata() {
        CreateChatMessageRequest request = CreateChatMessageRequest.builder().agentId(OTHER).sessionId(SESSION)
                .role(ChatMessageDTO.RoleType.USER).content("hello").build();
        assertEquals(400, assertThrows(AuthException.class, () -> access.validateChatMessage(request)).getStatus());
        request.setAgentId(OWN);
        request.setRole(ChatMessageDTO.RoleType.ASSISTANT);
        assertEquals(400, assertThrows(AuthException.class, () -> access.validateChatMessage(request)).getStatus());
        request.setRole(ChatMessageDTO.RoleType.TOOL);
        assertEquals(400, assertThrows(AuthException.class, () -> access.validateChatMessage(request)).getStatus());
        request.setRole(ChatMessageDTO.RoleType.SYSTEM);
        assertEquals(400, assertThrows(AuthException.class, () -> access.validateChatMessage(request)).getStatus());
        request.setRole(ChatMessageDTO.RoleType.USER);
        request.setMetadata(ChatMessageDTO.MetaData.builder().build());
        assertEquals(400, assertThrows(AuthException.class, () -> access.validateChatMessage(request)).getStatus());
        verifyNoInteractions(models);
    }
    @Test void assistantAndToolMessagesCannotBeEditedAsIfTheyWereUserInput() {
        when(messages.selectById(OWN)).thenReturn(ChatMessage.builder().id(OWN).sessionId(SESSION).role("assistant").build());
        assertEquals(403, assertThrows(AuthException.class, () -> access.requireMessage(OWN, true)).getStatus());
    }
    @Test void asynchronousValidationHasItsOwnIdentityAndNeverNeedsARequestThreadLocal() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertThrows(AuthException.class, AuthContext::requireUser);
                access.validateRuntime(USER, 1, HASH, SESSION, config());
            }).get();
        }
        verify(auth).currentActor(USER, 1, HASH);
        verify(models).validateLlmSelection("provided-model");
    }
    @Test void removingPublicationRevokesAUsersRuntimeAccess() {
        when(sessions.selectById(SESSION)).thenReturn(ChatSession.builder().id(SESSION).agentId(PUBLIC).build());
        AgentDTO config = config(); config.setId(PUBLIC); config.setAllowedKbs(List.of(PUBLIC));
        config.setAllowedTools(List.of("dataBaseTool"));
        access.validateRuntime(USER, 1, HASH, SESSION, config);
        assertTrue(config.getAllowedTools().isEmpty());
        publication.setPublicKbIds(List.of());
        assertEquals(404, assertThrows(AuthException.class, () -> access.validateRuntime(USER, 1, HASH, SESSION, config)).getStatus());
    }
    @Test void adminCanManageButCannotExecuteAnotherUsersOrVisitorsConversation() {
        AuthUser admin = new AuthUser(USER, "admin", "Admin", "ADMIN", true, Instant.now());
        AuthContext.set(new AuthRepository.LoginSession(admin, HASH, "csrf", Instant.now().plusSeconds(300), 1));
        when(auth.currentActor(USER, 1, HASH)).thenReturn(admin);
        when(acl.owner(ResourceAccess.SESSION, SESSION)).thenReturn(OTHER);
        assertNotNull(access.requireSession(SESSION));
        assertEquals(403, assertThrows(AuthException.class, () -> access.validateRuntime(USER, 1, HASH, SESSION, config())).getStatus());
        var request = CreateChatMessageRequest.builder().agentId(OWN).sessionId(SESSION).role(ChatMessageDTO.RoleType.USER).content("query").build();
        assertEquals(403, assertThrows(AuthException.class, () -> access.validateChatMessage(request)).getStatus());
        when(acl.owner(ResourceAccess.SESSION, SESSION)).thenReturn(null);
        when(sessions.selectById(SESSION)).thenReturn(ChatSession.builder().id(SESSION).agentId(OWN)
                .metadata("{\"demoOwner\":\"visitor\"}").build());
        assertEquals(403, assertThrows(AuthException.class, () -> access.validateRuntime(USER, 1, HASH, SESSION, config())).getStatus());
        verifyNoInteractions(models);
    }
    private AgentDTO config() {
        return AgentDTO.builder().id(OWN).name("助手").model("provided-model").allowedKbs(List.of(OWN))
                .allowedTools(List.of()).chatOptions(AgentDTO.ChatOptions.defaultOptions()).build();
    }
}
