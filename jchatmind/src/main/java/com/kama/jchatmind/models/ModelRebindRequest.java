package com.kama.jchatmind.models;

import java.util.List;

/**
 * 迁移知识库到某个向量模型的请求。
 *
 * <p>{@code vectorSpaceUnchanged} 必须显式为 true：只改绑定关系而不重建向量，前提是新旧模型是
 * 同一向量空间（同一模型的改名/换别名）。换了模型、供应商或维度时迁移不会报错，但检索结果会静默失准，
 * 所以这里强制调用方确认，而不是靠提示文案。</p>
 */
public record ModelRebindRequest(Boolean vectorSpaceUnchanged, List<String> knowledgeBaseIds) {

    @Override
    public String toString() {
        return "ModelRebindRequest[vectorSpaceUnchanged=" + vectorSpaceUnchanged
                + ", knowledgeBaseIds=" + (knowledgeBaseIds == null ? "all" : knowledgeBaseIds.size()) + "]";
    }
}
