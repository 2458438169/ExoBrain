package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.demo.DemoAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * worker 与发布路径的资源复核：开始处理前、每次续租以及发布事务内都要重新检查，而不是在任务提交时
 * 只 {@code exists} 一次。检查项与 HTTP 路径的 {@code ResourceAccess} 保持一致：
 *
 * <ul>
 *   <li>KB 与文档仍然存在（删除后任务行会随外键级联消失，这里再兜一层）；</li>
 *   <li>账号仍然启用（退出登录不影响，停用/删除才取消，见 ADR 0006）；</li>
 *   <li>账号仍然拥有该 KB，或管理员对公开 KB 的既有权限；后台线程不冒用管理员身份，
 *       身份来自任务里的 {@code actor_user_id}，不是请求或线程上下文。</li>
 * </ul>
 */
@Component
public class IngestionAccessGuard {

    private final JdbcTemplate jdbc;
    private final DemoAccessPolicy publication;

    public IngestionAccessGuard(JdbcTemplate jdbc, DemoAccessPolicy publication) {
        this.jdbc = jdbc;
        this.publication = publication;
    }

    /** 复核任务对应的资源与账号；返回 {@code null} 表示可以继续。 */
    public IngestionErrorCode evaluate(IngestionJob job) {
        return evaluate(job.id());
    }

    public IngestionErrorCode evaluate(String jobId) {
        List<ResourceState> rows = jdbc.query("SELECT j.status AS job_status, "
                        + " (d.id IS NOT NULL) AS document_exists, (kb.id IS NOT NULL) AS kb_exists, "
                        + " u.enabled AS actor_enabled, u.role AS actor_role, "
                        + " (acl.owner_id IS NOT NULL) AS actor_owns_kb, j.kb_id::text AS kb_id "
                        + "FROM exobrain_ingestion_job j "
                        + "LEFT JOIN document d ON d.id = j.document_id AND d.kb_id = j.kb_id "
                        + "LEFT JOIN knowledge_base kb ON kb.id = j.kb_id "
                        + "LEFT JOIN exobrain_user u ON u.id = j.actor_user_id "
                        + "LEFT JOIN exobrain_resource_acl acl ON acl.resource_type = 'KB' "
                        + "  AND acl.resource_id = j.kb_id AND acl.owner_id = j.actor_user_id "
                        + "WHERE j.id = CAST(? AS uuid)",
                (rs, row) -> new ResourceState(rs.getBoolean("document_exists"), rs.getBoolean("kb_exists"),
                        (Boolean) rs.getObject("actor_enabled"), rs.getString("actor_role"),
                        rs.getBoolean("actor_owns_kb"), rs.getString("kb_id")), jobId);
        if (rows.isEmpty()) {
            return IngestionErrorCode.DOCUMENT_DELETED;
        }
        ResourceState state = rows.get(0);
        if (!state.kbExists()) {
            return IngestionErrorCode.KNOWLEDGE_BASE_DELETED;
        }
        if (!state.documentExists()) {
            return IngestionErrorCode.DOCUMENT_DELETED;
        }
        if (Boolean.FALSE.equals(state.actorEnabled())) {
            return IngestionErrorCode.ACCOUNT_DISABLED;
        }
        boolean adminOnPublicKb = state.admin() && publication.isPublicKb(state.kbId());
        if (state.actorEnabled() != null && !state.ownsKb() && !adminOnPublicKb) {
            return IngestionErrorCode.NOT_AUTHORIZED;
        }
        return null;
    }

    private record ResourceState(boolean documentExists, boolean kbExists, Boolean actorEnabled, String actorRole,
                                 boolean ownsKb, String kbId) {
        boolean admin() {
            return actorRole != null && "ADMIN".equals(actorRole.toUpperCase(Locale.ROOT));
        }
    }
}
