package com.kama.jchatmind.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code chunk_bge_m3.metadata}（TEXT 列，不是 JSONB）里受控 JSON 的编解码。
 *
 * <p>只写入本节需要的字段；旧行的 NULL 或任意旧值都必须可读：解析失败时返回“没有位置”的空值，
 * 绝不猜测行号或 hash。legacy 行保持 metadata 为 NULL，不写任何位置信息。</p>
 */
public final class ChunkMetadata {

    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> headingPath;
    private final Integer startLine;
    private final Integer endLine;
    private final String sourceHash;
    private final String indexVersion;
    private final String documentName;

    public ChunkMetadata(List<String> headingPath, Integer startLine, Integer endLine, String sourceHash,
                         String indexVersion, String documentName) {
        this.headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        this.startLine = startLine;
        this.endLine = endLine;
        this.sourceHash = sourceHash;
        this.indexVersion = indexVersion;
        this.documentName = documentName;
    }

    /** 位置不可用的空值（legacy 行或无法解析的旧 metadata）。 */
    public static ChunkMetadata withoutPosition(String documentName) {
        return new ChunkMetadata(List.of(), null, null, null, null, documentName);
    }

    public static String encode(ChunkDraft chunk, IndexVersion version, String documentName) {
        ObjectNode node = JSON.createObjectNode();
        node.put("v", VERSION);
        ArrayNode path = node.putArray("headingPath");
        for (String heading : chunk.headingPath()) {
            path.add(heading);
        }
        node.put("startLine", chunk.startLine());
        node.put("endLine", chunk.endLine());
        node.put("sourceHash", chunk.sourceHash());
        node.put("indexVersion", version.wire());
        node.put("ordinal", chunk.ordinal());
        if (documentName != null) {
            node.put("documentName", documentName);
        }
        return node.toString();
    }

    /** 任何解析失败都降级为“没有位置”，不抛出、不猜测。 */
    public static ChunkMetadata decode(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return withoutPosition(null);
        }
        try {
            JsonNode node = JSON.readTree(metadata);
            if (node == null || !node.isObject()) {
                return withoutPosition(null);
            }
            List<String> path = new ArrayList<>();
            JsonNode headingPath = node.path("headingPath");
            if (headingPath.isArray()) {
                for (JsonNode heading : headingPath) {
                    if (heading.isTextual()) {
                        path.add(heading.asText());
                    }
                }
            }
            Integer start = positiveIntOrNull(node.get("startLine"));
            Integer end = positiveIntOrNull(node.get("endLine"));
            if (start == null || end == null || end < start) {
                start = null;
                end = null;
            }
            String sourceHash = node.path("sourceHash").isTextual() ? node.path("sourceHash").asText() : null;
            String indexVersion = node.path("indexVersion").isTextual() ? node.path("indexVersion").asText() : null;
            String documentName = node.path("documentName").isTextual() ? node.path("documentName").asText() : null;
            return new ChunkMetadata(path, start, end, sourceHash, indexVersion, documentName);
        } catch (Exception unreadable) {
            // 旧数据不可解析时只能降级为“无位置”，界面打开整份文档。
            return withoutPosition(null);
        }
    }

    private static Integer positiveIntOrNull(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        int value = node.asInt();
        return value >= 1 ? value : null;
    }

    public List<String> headingPath() {
        return headingPath;
    }

    public Integer startLine() {
        return startLine;
    }

    public Integer endLine() {
        return endLine;
    }

    public String sourceHash() {
        return sourceHash;
    }

    public String indexVersion() {
        return indexVersion;
    }

    public String documentName() {
        return documentName;
    }
}