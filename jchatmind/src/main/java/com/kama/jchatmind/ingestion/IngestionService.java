package com.kama.jchatmind.ingestion;

import java.util.Map;
import java.util.Optional;

/**
 * 导入任务的对外服务（状态查询、重试、提交）。授权在 Controller 层按 KB 读/写权限完成，
 * 这里不读取请求身份，也不从请求参数推导资源归属。
 */
public interface IngestionService {

    /** 在调用方的提交事务内登记一条 {@code QUEUED} 任务，返回任务 id。 */
    String submit(String kbId, String documentId, String filePath, String sourceHash, String actorUserId);

    Optional<IngestionJobView> status(String documentId);

    /** KB 下每个文档的最新状态（文档列表一次查询即可，避免逐文档轮询）。 */
    Map<String, IngestionJobView> statusesByKnowledgeBase(String kbId);

    /**
     * 重新排队最近一次失败的导入。若已有进行中的任务则直接返回它（幂等，不会生成第二个任务）；
     * 调用方必须先通过 KB 写权限校验（只读的公开文档不能重试）。
     */
    IngestionJobView retry(String documentId);
}
