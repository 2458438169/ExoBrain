package com.kama.jchatmind.retrieval;

import java.util.List;

/**
 * 索引构建与发布服务：任务 03 使用，任务 06 直接复用。
 *
 * <p>边界（契约 §3）：构建阶段读取固定快照、切块并在<b>事务之外</b>调用 Embedding；
 * 发布阶段是<b>短事务</b>：锁住 KB、核对文档清单 / 源 hash / 模型绑定 / 活动版本，然后原子切换
 * （插入新版本 chunk → 标记 PUBLISHED → 旧版本 SUPERSEDED → 写入活动状态行）。任何一步失败都保持旧索引可用。</p>
 *
 * <p>调用方不得在自己打开的数据库事务里等待外部 Embedding：{@link #rebuild} / {@link #indexDocument}
 * 已经按这个顺序编排；{@link #publish} 会加入调用方已有的事务（上传路径就是这样，其长事务限制见交付记录），
 * 也可以独立调用（独立调用时它就是一次短事务）。</p>
 */
public interface RetrievalIndexService {

    /** 解析 KB 当前的活动索引范围（有状态行用状态行；旧库保持 legacy；空库用 body-v1）。 */
    IndexScope resolveScope(String kbId);

    /** 生成“整个 KB 重建”的候选（只切块，不调用外部服务，不写数据库）。 */
    IndexCandidate prepareRebuild(String kbId, List<SourceDocument> documents);

    /** 生成“新增一份文档”的候选：未变化文档从活动版本复制，新文档重新切块。 */
    IndexCandidate prepareDocument(String kbId, SourceDocument document);

    /** 调用 KB 绑定的向量模型生成候选向量；不写数据库，失败时不会留下半份索引。 */
    IndexCandidate embedCandidate(IndexCandidate candidate);

    /** 原子发布候选（短事务）。 */
    PublishedIndex publish(IndexCandidate candidate);

    /** 重建整个 KB：准备 → Embedding → 发布，失败时登记一条 FAILED 版本并保持旧索引可用。 */
    PublishedIndex rebuild(String kbId, List<SourceDocument> documents);

    /** 上传路径：把一份新文档并入该 KB 当前的活动版本。 */
    PublishedIndex indexDocument(String kbId, SourceDocument document);

    /** 清理被替换的版本（保留最近若干次），活动版本永远不会被删。 */
    int cleanupSuperseded(String kbId, int keepNewestRevisions);

    /** 文档删除后刷新活动版本的计数与来源摘要（计数按事实重算，摘要置空而不是伪造）。 */
    void refreshActiveRevision(String kbId);
}