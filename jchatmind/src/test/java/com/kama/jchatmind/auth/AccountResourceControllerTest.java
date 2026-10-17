package com.kama.jchatmind.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.controller.*;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoProperties;
import com.kama.jchatmind.mapper.*;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.response.*;
import com.kama.jchatmind.model.vo.*;
import com.kama.jchatmind.models.ModelCatalogService;
import com.kama.jchatmind.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountResourceControllerTest {
    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String OWN = "22222222-2222-4222-8222-222222222222";
    private static final String OTHER = "33333333-3333-4333-8333-333333333333";
    private static final String PUBLIC = "44444444-4444-4444-8444-444444444444";
    private final ResourceAclRepository acl = mock(ResourceAclRepository.class);
    private final AgentFacadeService agents = mock(AgentFacadeService.class);
    private final KnowledgeBaseFacadeService kbs = mock(KnowledgeBaseFacadeService.class);
    private final DocumentFacadeService documents = mock(DocumentFacadeService.class);
    private final ChatSessionFacadeService sessions = mock(ChatSessionFacadeService.class);
    private final ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
    private final SseService sse = mock(SseService.class);
    private final AuthService auth = mock(AuthService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        ObjectMapper json = new ObjectMapper();
        var demo = new DemoProperties(); demo.setAgentId(PUBLIC); demo.setPublicKbIds(List.of(PUBLIC));
        var docMapper = mock(DocumentMapper.class);
        var sessionMapper = mock(ChatSessionMapper.class);
        when(acl.owns(USER, ResourceAccess.AGENT, OWN)).thenReturn(true);
        when(acl.owns(USER, ResourceAccess.KB, OWN)).thenReturn(true);
        when(acl.owns(USER, ResourceAccess.SESSION, OWN)).thenReturn(true);
        when(acl.owner(ResourceAccess.AGENT, OTHER)).thenReturn(OTHER);
        when(acl.owner(ResourceAccess.SESSION, OTHER)).thenReturn(OTHER);
        when(docMapper.selectById(OTHER)).thenReturn(Document.builder().id(OTHER).kbId(OTHER).build());
        when(docMapper.selectById(PUBLIC)).thenReturn(Document.builder().id(PUBLIC).kbId(PUBLIC).build());
        when(sessionMapper.selectById(OWN)).thenReturn(ChatSession.builder().id(OWN).agentId(OWN).build());
        var converter = new AgentConverter(json);
        var access = new ResourceAccess(acl, new DemoAccessPolicy(demo), demo, mock(AgentMapper.class), converter,
                mock(KnowledgeBaseMapper.class), docMapper, sessionMapper, mock(ChatMessageMapper.class), auth,
                mock(ModelCatalogService.class));
        var properties = new AuthProperties();
        when(auth.authenticate("session")).thenReturn(new AuthRepository.LoginSession(
                new AuthUser(USER, "demo", "体验", "USER", true, Instant.now()), "hash", "csrf", Instant.now().plusSeconds(100), 1));
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(agents, access, converter),
                        new KnowledgeBaseController(kbs, access), new DocumentController(documents,
                                mock(com.kama.jchatmind.ingestion.IngestionService.class), access),
                        new ChatSessionController(sessions, access), new ChatMessageController(messages, access),
                        new SseController(sse, access, mock(AuthenticatedStreams.class)), new ToolController(mock(ToolFacadeService.class)))
                .defaultRequest(get("/").cookie(new Cookie(properties.cookieName(), "session")).header("X-CSRF-Token", "csrf"))
                .addFilters(new AuthFilter(auth, properties, json)).setControllerAdvice(new AuthExceptionHandler()).build();
    }
    @Test void actualListEndpointsFilterPrivateResourcesAndMarkSharedItemsReadOnly() throws Exception {
        when(agents.getAgents()).thenReturn(GetAgentsResponse.builder().agents(new AgentVO[]{
                agent(OWN), agent(OTHER), agent(PUBLIC)}).build());
        when(kbs.getKnowledgeBases()).thenReturn(GetKnowledgeBasesResponse.builder().knowledgeBases(new KnowledgeBaseVO[]{
                KnowledgeBaseVO.builder().id(OWN).build(), KnowledgeBaseVO.builder().id(OTHER).build(),
                KnowledgeBaseVO.builder().id(PUBLIC).build()}).build());
        when(sessions.getChatSessions()).thenReturn(GetChatSessionsResponse.builder().chatSessions(new ChatSessionVO[]{
                ChatSessionVO.builder().id(OWN).build(), ChatSessionVO.builder().id(OTHER).build(),
                ChatSessionVO.builder().id(PUBLIC).build()}).build());
        mvc.perform(get("/api/agents")).andExpect(status().isOk()).andExpect(jsonPath("$.data.agents.length()").value(2))
                .andExpect(jsonPath("$.data.agents[0].id").value(OWN)).andExpect(jsonPath("$.data.agents[0].readOnly").value(false))
                .andExpect(jsonPath("$.data.agents[1].id").value(PUBLIC)).andExpect(jsonPath("$.data.agents[1].readOnly").value(true))
                .andExpect(jsonPath("$.data.agents[1].systemPrompt").doesNotExist());
        mvc.perform(get("/api/knowledge-bases")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeBases.length()").value(2))
                .andExpect(jsonPath("$.data.knowledgeBases[1].readOnly").value(true));
        mvc.perform(get("/api/chat-sessions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatSessions.length()").value(1))
                .andExpect(jsonPath("$.data.chatSessions[0].id").value(OWN));
        mvc.perform(get("/api/tools")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }
    @Test void actualMutationEndpointsCannotChangeOrUploadIntoSharedResources() throws Exception {
        for (String resource : new String[]{"agents", "knowledge-bases", "documents"}) {
            mvc.perform(delete("/api/" + resource + "/" + PUBLIC)).andExpect(status().isForbidden());
            mvc.perform(patch("/api/" + resource + "/" + PUBLIC).contentType("application/json").content("{\"name\":\"changed\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(multipart("/api/documents/upload").file(new MockMultipartFile("file", "notes.md", "text/markdown", "# hello".getBytes()))
                        .param("kbId", PUBLIC)).andExpect(status().isForbidden());
        mvc.perform(post("/api/documents").contentType("application/json")
                        .content("{\"kbId\":\"" + OWN + "\",\"filename\":\"notes.md\",\"metadata\":{\"filePath\":\"secret\"}}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(agents, kbs, documents);
    }
    @Test void administratorEndpointsRejectAnotherAccountsKnowledgeBaseAndDocuments() throws Exception {
        when(auth.authenticate("session")).thenReturn(new AuthRepository.LoginSession(
                new AuthUser(USER, "admin", "管理员", "ADMIN", true, Instant.now()), "hash", "csrf", Instant.now().plusSeconds(100), 1));
        when(kbs.getKnowledgeBases()).thenReturn(GetKnowledgeBasesResponse.builder().knowledgeBases(new KnowledgeBaseVO[]{
                KnowledgeBaseVO.builder().id(OWN).build(), KnowledgeBaseVO.builder().id(OTHER).build(),
                KnowledgeBaseVO.builder().id(PUBLIC).build()}).build());
        mvc.perform(get("/api/knowledge-bases")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeBases.length()").value(2))
                .andExpect(jsonPath("$.data.knowledgeBases[0].id").value(OWN))
                .andExpect(jsonPath("$.data.knowledgeBases[0].publicResource").value(false))
                .andExpect(jsonPath("$.data.knowledgeBases[1].id").value(PUBLIC))
                .andExpect(jsonPath("$.data.knowledgeBases[1].publicResource").value(true));
        mvc.perform(get("/api/documents/kb/" + OTHER)).andExpect(status().isNotFound());
        for (String resource : new String[]{"knowledge-bases", "documents", "agents"}) {
            mvc.perform(delete("/api/" + resource + "/" + OTHER)).andExpect(status().isNotFound());
            mvc.perform(patch("/api/" + resource + "/" + OTHER).contentType("application/json").content("{\"name\":\"changed\"}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(multipart("/api/documents/upload").file(new MockMultipartFile("file", "notes.md", "text/markdown", "# hello".getBytes()))
                        .param("kbId", OTHER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat-messages/session/" + OTHER)).andExpect(status().isNotFound());
        mvc.perform(get("/sse/connect/" + OTHER)).andExpect(status().isNotFound());
        verifyNoInteractions(documents, agents, messages, sse);
        verify(kbs, never()).deleteKnowledgeBase(anyString());
    }
    @Test void actualHistorySseAndMessageEndpointsRejectCrossUserIdsAndForgedRoles() throws Exception {
        mvc.perform(get("/api/chat-sessions/" + OTHER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat-messages/session/" + OTHER)).andExpect(status().isNotFound());
        mvc.perform(get("/sse/connect/" + OTHER)).andExpect(status().isNotFound());
        mvc.perform(post("/api/chat-messages").contentType("application/json").content("{\"agentId\":\"" + OWN
                        + "\",\"sessionId\":\"" + OWN + "\",\"role\":\"system\",\"content\":\"forged\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat-messages").contentType("application/json").content("{\"agentId\":\"" + PUBLIC
                        + "\",\"sessionId\":\"" + OWN + "\",\"role\":\"user\",\"content\":\"hello\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(messages, sessions, sse);
    }
    private AgentVO agent(String id) {
        return AgentVO.builder().id(id).name("助手").systemPrompt("private instructions")
                .allowedTools(List.of()).allowedKbs(List.of(PUBLIC)).build();
    }
}
