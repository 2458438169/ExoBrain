package com.kama.jchatmind.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.response.GetKnowledgeBasesResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.model.vo.KnowledgeBaseVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class DemoCatalogService {
    private final DemoAccessPolicy access;
    private final DemoSessionRepository sessions;
    private final KnowledgeBaseMapper knowledgeBases;
    private final DocumentMapper documents;
    private final ChatMessageFacadeService messages;
    private final ObjectMapper json;

    public record PublicTool(String name, String description, ToolType type) { }
    public record Showcases(List<ChatSessionVO> showcases) { }
    public record Showcase(String id, String agentId, String title, List<ChatMessageVO> chatMessages) { }

    public GetKnowledgeBasesResponse knowledgeBases() {
        List<String> ids = access.publicKbIds();
        if (ids.isEmpty()) return GetKnowledgeBasesResponse.builder().knowledgeBases(new KnowledgeBaseVO[0]).build();
        return GetKnowledgeBasesResponse.builder().knowledgeBases(knowledgeBases.selectByIdBatch(ids).stream()
                .filter(kb -> ids.contains(kb.getId()))
                .map(kb -> KnowledgeBaseVO.builder().id(kb.getId()).name(kb.getName())
                        .description(kb.getDescription()).build()).toArray(KnowledgeBaseVO[]::new)).build();
    }

    public GetDocumentsResponse documents(String kbId) {
        access.requirePublicKb(kbId);
        String publicId = UUID.fromString(kbId).toString();
        return GetDocumentsResponse.builder().documents(documents.selectByKbId(publicId).stream()
                .filter(document -> publicId.equals(document.getKbId()))
                .map(document -> DocumentVO.builder().id(document.getId()).kbId(publicId)
                        .filename(publicFilename(document.getFilename())).filetype(document.getFiletype())
                        .size(document.getSize()).build()).toArray(DocumentVO[]::new)).build();
    }

    public List<PublicTool> tools() {
        return List.of(
                new PublicTool("KnowledgeTool", "检索公开演示知识库，回答时说明资料来源。", ToolType.FIXED),
                new PublicTool("getDate", "读取服务器当前日期，适合测试真实工具调用。", ToolType.FIXED),
                new PublicTool("directAnswer", "将最终答复交付到对话中。", ToolType.FIXED),
                new PublicTool("terminate", "在任务完成后结束本轮执行。", ToolType.FIXED));
    }

    public Showcases showcases() {
        String agentId = access.agentId();
        return new Showcases(access.showcaseSessionIds().stream()
                .map(id -> sessions.findShowcase(id, agentId)).filter(Objects::nonNull).toList());
    }

    public Showcase showcase(String id) {
        if (!DemoAccessPolicy.validId(id) || !access.isShowcase(id)) throw missing();
        String sessionId = UUID.fromString(id).toString();
        ChatSessionVO session = sessions.findShowcase(sessionId, access.agentId());
        if (session == null) throw missing();
        ChatMessageVO[] history = messages.getChatMessagesBySessionId(sessionId).getChatMessages();
        Set<String> approvedCalls = Arrays.stream(history)
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT)
                .map(ChatMessageVO::getMetadata).filter(Objects::nonNull)
                .map(ChatMessageDTO.MetaData::getToolCalls).filter(Objects::nonNull).flatMap(List::stream)
                .map(call -> {
                    if (!publicCall(call) || call.id() == null) throw missing();
                    return call.id() + ":" + call.name();
                }).collect(Collectors.toSet());
        List<ChatMessageVO> publicHistory = Arrays.stream(history)
                .filter(message -> message.getRole() != ChatMessageDTO.RoleType.SYSTEM)
                .map(message -> publicMessage(sessionId, message, approvedCalls)).toList();
        return new Showcase(session.getId(), session.getAgentId(), session.getTitle(), publicHistory);
    }

    private ChatMessageVO publicMessage(String sessionId, ChatMessageVO message, Set<String> approvedCalls) {
        if (!sessionId.equals(message.getSessionId()) || message.getRole() == null) throw missing();
        ChatMessageDTO.MetaData metadata = null;
        if (message.getRole() == ChatMessageDTO.RoleType.ASSISTANT && message.getMetadata() != null) {
            List<AssistantMessage.ToolCall> calls = message.getMetadata().getToolCalls();
            if (calls != null && calls.stream().anyMatch(call -> !publicCall(call))) throw missing();
            metadata = ChatMessageDTO.MetaData.builder().toolCalls(calls).build();
        } else if (message.getRole() == ChatMessageDTO.RoleType.TOOL) {
            var response = message.getMetadata() == null ? null : message.getMetadata().getToolResponse();
            if (response == null || response.name() == null || !DemoAccessPolicy.PUBLIC_TOOL_NAMES.contains(response.name())
                    || !approvedCalls.contains(response.id() + ":" + response.name())) throw missing();
            metadata = ChatMessageDTO.MetaData.builder().toolResponse(response).build();
        }
        return ChatMessageVO.builder().id(message.getId()).sessionId(sessionId).role(message.getRole())
                .content(message.getContent()).metadata(metadata).build();
    }

    private boolean publicCall(AssistantMessage.ToolCall call) {
        if (call == null || call.name() == null || !DemoAccessPolicy.PUBLIC_TOOL_NAMES.contains(call.name())) return false;
        if (!"KnowledgeTool".equals(call.name())) return true;
        try {
            JsonNode args = json.readTree(call.arguments());
            return args != null && args.hasNonNull("kbsId") && access.isPublicKb(args.get("kbsId").asText());
        } catch (Exception invalidArguments) {
            return false;
        }
    }

    private static String publicFilename(String filename) {
        if (filename == null) return null;
        String normalized = filename.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static DemoException missing() { return new DemoException(404, "示例记录不存在"); }
}
