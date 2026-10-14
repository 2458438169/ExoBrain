package com.kama.jchatmind.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.SseService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DemoControllerTest {
    private static final String AGENT = "11111111-1111-4111-8111-111111111111";
    private static final String OWN_SESSION = "22222222-2222-4222-8222-222222222222";
    private static final String PRIVATE_SESSION = "33333333-3333-4333-8333-333333333333";
    private static final String PUBLIC_KB = "44444444-4444-4444-8444-444444444444";
    private static final String PRIVATE_KB = "55555555-5555-4555-8555-555555555555";
    private final Cookie first = new Cookie(DemoVisitor.COOKIE_NAME, "a".repeat(43));
    private final Cookie second = new Cookie(DemoVisitor.COOKIE_NAME, "b".repeat(43));
    private final MemorySessions sessions = new MemorySessions();
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentConverter converter = mock(AgentConverter.class);
    private final ChatMessageFacadeService messages = mock(ChatMessageFacadeService.class);
    private final SseService sse = mock(SseService.class);
    private final DemoExecutionService execution = mock(DemoExecutionService.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final DemoProperties properties = new DemoProperties();
    private AgentDTO agentConfig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        properties.setAgentId(AGENT);
        Agent entity = Agent.builder().id(AGENT).build();
        when(agents.selectById(AGENT)).thenReturn(entity);
        agentConfig = AgentDTO.builder().id(AGENT).name("Public demo")
                .systemPrompt("private system prompt").model(AgentDTO.ModelType.DEEPSEEK_CHAT.getModelName())
                .allowedTools(List.of()).allowedKbs(List.of()).chatOptions(AgentDTO.ChatOptions.defaultOptions()).build();
        when(converter.toDTO(entity)).thenReturn(agentConfig);
        sessions.insert(OWN_SESSION, AGENT, "My chat", DemoVisitor.hash(first.getValue()));
        sessions.insert(PRIVATE_SESSION, AGENT, "Private admin chat", null);
        when(messages.getChatMessagesBySessionId(OWN_SESSION)).thenReturn(
                GetChatMessagesResponse.builder().chatMessages(new ChatMessageVO[0]).build());
        when(messages.agentCreateChatMessage(any())).thenReturn(
                CreateChatMessageResponse.builder().chatMessageId("message").build());
        when(execution.submit(anyString(), anyString(), any())).thenAnswer(invocation -> {
            Supplier<CreateChatMessageResponse> persist = invocation.getArgument(2);
            return persist.get();
        });
        DemoAccessPolicy access = new DemoAccessPolicy(properties);
        DemoChatService demo = new DemoChatService(access, sessions, agents, converter, messages,
                sse, execution, new DemoRateLimiter());
        DemoCatalogService catalog = new DemoCatalogService(access, sessions, knowledgeBases, documents, messages,
                new ObjectMapper(), mock(DocumentFacadeService.class));
        mvc = MockMvcBuilders.standaloneSetup(new DemoController(demo, new DemoVisitor(), catalog)).build();
    }

    @Test
    void newVisitorGetsSecureHttpOnlyCookieAndOnlyPublicAgent() throws Exception {
        var response = mvc.perform(get("/api/demo/agents").secure(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.agents.length()").value(1))
                .andExpect(jsonPath("$.data.agents[0].id").value(AGENT))
                .andExpect(content().string(not(containsString("private system prompt"))))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        String cookie = response.getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Lax"));
        String token = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        verify(agents, never()).selectAll();
    }

    @Test
    void visitorsCannotListReadModifyOrSubscribeToOthersOrExistingPrivateSessions() throws Exception {
        mvc.perform(get("/api/demo/chat-sessions").cookie(first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.chatSessions.length()").value(1))
                .andExpect(jsonPath("$.data.chatSessions[0].id").value(OWN_SESSION))
                .andExpect(content().string(not(containsString("demoOwner"))))
                .andExpect(content().string(not(containsString(DemoVisitor.hash(first.getValue())))));
        mvc.perform(get("/api/demo/chat-sessions").cookie(second))
                .andExpect(jsonPath("$.data.chatSessions.length()").value(0));
        for (String id : List.of(OWN_SESSION, PRIVATE_SESSION)) {
            mvc.perform(get("/api/demo/chat-sessions/" + id).cookie(second)).andExpect(status().isNotFound());
            mvc.perform(get("/api/demo/chat-messages/session/" + id).cookie(second)).andExpect(status().isNotFound());
            mvc.perform(get("/sse/demo/connect/" + id).cookie(second)).andExpect(status().isNotFound());
            mvc.perform(delete("/api/demo/chat-sessions/" + id).cookie(second)).andExpect(status().isNotFound());
            mvc.perform(patch("/api/demo/chat-sessions/" + id).cookie(second).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"hijacked\"}")).andExpect(status().isNotFound());
            mvc.perform(post("/api/demo/chat-messages").cookie(second).contentType(MediaType.APPLICATION_JSON)
                    .content(messageBody("user", "hijack").replace(OWN_SESSION, id))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/demo/chat-sessions/" + PRIVATE_SESSION).cookie(first)).andExpect(status().isNotFound());
        verifyNoInteractions(sse, messages);
    }

    @Test
    void publicCatalogIncludesOnlyPublishedKnowledgeAndNeverStorageMetadata() throws Exception {
        properties.setPublicKbIds(List.of(PUBLIC_KB));
        agentConfig.setAllowedKbs(List.of(PUBLIC_KB));
        when(knowledgeBases.selectByIdBatch(List.of(PUBLIC_KB))).thenReturn(List.of(
                KnowledgeBase.builder().id(PUBLIC_KB).name("公开项目介绍").description("可公开资料")
                        .metadata("{\"internal\":\"private metadata\"}").build(),
                KnowledgeBase.builder().id(PRIVATE_KB).name("Private KB").build()));
        when(documents.selectByKbId(PUBLIC_KB)).thenReturn(List.of(
                Document.builder().id("doc").kbId(PUBLIC_KB).filename("C:\\private\\storage\\guide.md")
                        .filetype("md").size(120L).metadata("{\"filePath\":\"/private/storage/guide.md\"}").build()));
        mvc.perform(get("/api/demo/agents").cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agents[0].allowedKbs[0]").value(PUBLIC_KB))
                .andExpect(content().string(not(containsString("private system prompt"))));
        mvc.perform(get("/api/demo/knowledge-bases").cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeBases.length()").value(1))
                .andExpect(jsonPath("$.data.knowledgeBases[0].id").value(PUBLIC_KB))
                .andExpect(content().string(not(containsString("private metadata"))));
        mvc.perform(get("/api/demo/documents/kb/" + PUBLIC_KB).cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documents[0].filename").value("guide.md"))
                .andExpect(content().string(not(containsString("storage"))))
                .andExpect(content().string(not(containsString("metadata"))));
        mvc.perform(get("/api/demo/documents/kb/" + PRIVATE_KB).cookie(first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/demo/documents/kb/invalid").cookie(first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/demo/tools").cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "KnowledgeTool", "getDate", "directAnswer", "terminate")));
        verify(knowledgeBases, never()).selectAll();
        verify(documents, never()).selectAll();
        verify(documents, never()).selectByKbId(PRIVATE_KB);
    }

    @Test
    void privateKnowledgeConfigurationFailsEvenWhenManagementToolsAreSelected() throws Exception {
        properties.setPublicKbIds(List.of(PUBLIC_KB));
        agentConfig.setAllowedKbs(List.of(PRIVATE_KB));
        agentConfig.setAllowedTools(List.of("dataBaseTool", "emailTool"));
        mvc.perform(get("/api/demo/agents").cookie(first)).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/demo/chat-messages").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content(messageBody("user", "read private KB"))).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(messages, execution, knowledgeBases);
    }

    @Test
    void managementToolSelectionsDoNotDisableThePublicAgentOrChangeStoredConfiguration() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        AgentConverter realConverter = new AgentConverter(json);
        String selectedTools = "[\"dataBaseTool\",\"emailTool\",\"untrusted::tool\"]";
        Agent stored = Agent.builder().id(AGENT).name("Public demo").model("deepseek-chat")
                .allowedTools(selectedTools).allowedKbs("[]")
                .chatOptions(json.writeValueAsString(AgentDTO.ChatOptions.defaultOptions())).build();
        when(agents.selectById(AGENT)).thenReturn(stored);
        when(converter.toDTO(stored)).thenAnswer(invocation -> realConverter.toDTO(stored));

        mvc.perform(get("/api/demo/agents").cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agents[0].allowedTools.length()").value(0))
                .andExpect(content().string(not(containsString("dataBaseTool"))));
        mvc.perform(post("/api/demo/chat-messages").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content(messageBody("user", "hello"))).andExpect(status().isOk());
        assertEquals(selectedTools, stored.getAllowedTools());
        assertEquals(List.of("dataBaseTool", "emailTool", "untrusted::tool"), realConverter.toDTO(stored).getAllowedTools());
        verify(agents, never()).updateById(any());
        verify(agents, never()).insert(any());
        verify(agents, never()).deleteById(anyString());
    }

    @Test
    void showcaseRequiresPublicationAndDemoOwnershipPreservesToolRecordsAndIsReadOnly() throws Exception {
        properties.setShowcaseSessionIds(List.of(OWN_SESSION, PRIVATE_SESSION));
        when(messages.getChatMessagesBySessionId(OWN_SESSION)).thenReturn(GetChatMessagesResponse.builder()
                .chatMessages(new ChatMessageVO[]{
                        ChatMessageVO.builder().sessionId(OWN_SESSION).role(ChatMessageDTO.RoleType.SYSTEM)
                                .content("private system prompt").build(),
                        ChatMessageVO.builder().id("user").sessionId(OWN_SESSION).role(ChatMessageDTO.RoleType.USER)
                                .content("今天几号？").build(),
                        ChatMessageVO.builder().id("assistant").sessionId(OWN_SESSION).role(ChatMessageDTO.RoleType.ASSISTANT)
                                .content("").metadata(ChatMessageDTO.MetaData.builder().toolCalls(List.of(
                                        new AssistantMessage.ToolCall("call-1", "function", "getDate", "{}"))).build()).build(),
                        ChatMessageVO.builder().id("tool").sessionId(OWN_SESSION).role(ChatMessageDTO.RoleType.TOOL)
                                .content("2026-10-14").metadata(ChatMessageDTO.MetaData.builder().toolResponse(
                                        new ToolResponseMessage.ToolResponse("call-1", "getDate", "2026-10-14")).build()).build()
                }).build());
        mvc.perform(get("/api/demo/showcase").cookie(second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.showcases.length()").value(1))
                .andExpect(jsonPath("$.data.showcases[0].id").value(OWN_SESSION))
                .andExpect(content().string(not(containsString("demoOwner"))));
        mvc.perform(get("/api/demo/showcase/" + OWN_SESSION).cookie(second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatMessages.length()").value(3))
                .andExpect(jsonPath("$.data.chatMessages[1].metadata.toolCalls[0].name").value("getDate"))
                .andExpect(jsonPath("$.data.chatMessages[2].metadata.toolResponse.responseData").value("2026-10-14"))
                .andExpect(content().string(not(containsString("private system prompt"))));
        mvc.perform(get("/api/demo/showcase/" + PRIVATE_SESSION).cookie(second)).andExpect(status().isNotFound());
        mvc.perform(get("/api/demo/chat-messages/session/" + OWN_SESSION).cookie(second)).andExpect(status().isNotFound());
        mvc.perform(post("/api/demo/chat-messages").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content(messageBody("user", "private addition"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/demo/chat-sessions/" + OWN_SESSION).cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"changed\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/demo/chat-sessions/" + OWN_SESSION).cookie(first)).andExpect(status().isForbidden());
        mvc.perform(get("/sse/demo/connect/" + OWN_SESSION).cookie(first)).andExpect(status().isForbidden());
        mvc.perform(post("/api/demo/showcase/" + OWN_SESSION).cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        verify(messages, never()).getChatMessagesBySessionId(PRIVATE_SESSION);
        verifyNoInteractions(sse, execution);
    }

    @Test
    void unpublishedHistoryAndUnsafeToolMetadataCannotBeReadThroughShowcase() throws Exception {
        mvc.perform(get("/api/demo/showcase/" + OWN_SESSION).cookie(second)).andExpect(status().isNotFound());
        verifyNoInteractions(messages);
        properties.setShowcaseSessionIds(List.of(OWN_SESSION));
        for (AssistantMessage.ToolCall call : List.of(
                new AssistantMessage.ToolCall("secret", "function", "readFile", "{\"path\":\"/private/file\"}"),
                new AssistantMessage.ToolCall("secret", "function", "KnowledgeTool", "{\"kbsId\":\"" + PRIVATE_KB + "\"}"))) {
            when(messages.getChatMessagesBySessionId(OWN_SESSION)).thenReturn(GetChatMessagesResponse.builder()
                    .chatMessages(new ChatMessageVO[]{ChatMessageVO.builder().id("private-tool").sessionId(OWN_SESSION)
                            .role(ChatMessageDTO.RoleType.ASSISTANT).content("private data")
                            .metadata(ChatMessageDTO.MetaData.builder().toolCalls(List.of(call)).build()).build()}).build());
            mvc.perform(get("/api/demo/showcase/" + OWN_SESSION).cookie(second)).andExpect(status().isNotFound())
                    .andExpect(content().string(not(containsString("private data"))));
        }
    }

    @Test
    void rejectsAdminCrudAgentSpoofingRoleSpoofingAndOversizeMessages() throws Exception {
        for (String path : List.of("agents", "knowledge-bases", "documents/upload")) {
            mvc.perform(post("/api/demo/" + path).cookie(first).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(patch("/api/demo/agents/" + AGENT).cookie(first).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/demo/chat-messages/message").cookie(first)).andExpect(status().isForbidden());
        mvc.perform(post("/api/demo/chat-sessions").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"private-agent\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/demo/chat-messages").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content(messageBody("system", "hello"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/demo/chat-messages").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content(messageBody("user", "x".repeat(4001)))).andExpect(status().isBadRequest());
        verifyNoInteractions(execution, messages);
    }

    @Test
    void validMessagesAreForcedToUserAndCannotInjectMetadataOrPublishGlobalAgentEvent() throws Exception {
        String body = messageBody("user", "hello").replace("}", ",\"metadata\":{\"toolCalls\":[{}]}}");
        mvc.perform(post("/api/demo/chat-messages").cookie(first).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.chatMessageId").value("message"));
        ArgumentCaptor<CreateChatMessageRequest> captured = ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(messages).agentCreateChatMessage(captured.capture());
        assertEquals(ChatMessageDTO.RoleType.USER, captured.getValue().getRole());
        assertEquals(AGENT, captured.getValue().getAgentId());
        assertNull(captured.getValue().getMetadata());
        verify(messages, never()).createChatMessage(any(CreateChatMessageRequest.class));
        mvc.perform(post("/api/demo/chat-messages").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content(messageBody("user", "again"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void newSessionsKeepHashedOwnershipAndEnforceTwentySessionLimit() throws Exception {
        for (int i = 1; i < 20; i++) {
            mvc.perform(post("/api/demo/chat-sessions").cookie(first).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentId\":\"" + AGENT + "\",\"title\":\"New chat\"}"))
                    .andExpect(status().isOk());
        }
        assertEquals(20, sessions.list(AGENT, DemoVisitor.hash(first.getValue())).size());
        assertTrue(sessions.owners.values().stream().filter(Objects::nonNull).allMatch(owner -> owner.length() == 64));
        mvc.perform(post("/api/demo/chat-sessions").cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"" + AGENT + "\"}")).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/demo/chat-sessions").cookie(second)).andExpect(jsonPath("$.data.chatSessions.length()").value(0));
    }

    private String messageBody(String role, String content) {
        return "{\"agentId\":\"" + AGENT + "\",\"sessionId\":\"" + OWN_SESSION
                + "\",\"role\":\"" + role + "\",\"content\":\"" + content + "\"}";
    }

    private static class MemorySessions extends DemoSessionRepository {
        final Map<String, ChatSessionVO> values = new HashMap<>();
        final Map<String, String> owners = new HashMap<>();
        MemorySessions() { super(null); }
        @Override public void lockOwner(String owner) { }
        @Override public void insert(String id, String agent, String title, String owner) {
            values.put(id, ChatSessionVO.builder().id(id).agentId(agent).title(title).build());
            owners.put(id, owner);
        }
        @Override public List<ChatSessionVO> list(String agent, String owner) {
            return values.values().stream().filter(value -> agent.equals(value.getAgentId())
                    && owner.equals(owners.get(value.getId()))).toList();
        }
        @Override public ChatSessionVO find(String id, String agent, String owner) {
            ChatSessionVO value = values.get(id);
            return value != null && agent.equals(value.getAgentId()) && owner.equals(owners.get(id)) ? value : null;
        }
        @Override public ChatSessionVO findShowcase(String id, String agent) {
            ChatSessionVO value = values.get(id);
            String owner = owners.get(id);
            return value != null && agent.equals(value.getAgentId()) && owner != null && owner.matches("[a-f0-9]{64}")
                    ? value : null;
        }
        @Override public boolean rename(String id, String agent, String owner, String title) {
            ChatSessionVO value = find(id, agent, owner);
            if (value == null) return false;
            value.setTitle(title);
            return true;
        }
        @Override public boolean delete(String id, String agent, String owner) {
            if (find(id, agent, owner) == null) return false;
            values.remove(id);
            owners.remove(id);
            return true;
        }
    }
}
