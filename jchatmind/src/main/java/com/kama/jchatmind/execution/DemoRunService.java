package com.kama.jchatmind.execution;

import com.kama.jchatmind.demo.DemoAccessPolicy;
import com.kama.jchatmind.demo.DemoException;
import com.kama.jchatmind.demo.DemoSessionRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 游客运行记录的受权读取。
 *
 * <p>只允许两种访问：访客自己的会话（{@code exobrain_demo} Cookie 的哈希），或已发布到
 * {@code app.demo.showcase-session-ids} 的公开精选会话。其余一律 404，且不区分“不存在”与
 * “无权访问”。公开精选使用 {@link RunQueryService} 的白名单投影。</p>
 */
@Service
public class DemoRunService {

    private final DemoAccessPolicy access;
    private final DemoSessionRepository sessions;
    private final RunQueryService query;

    public DemoRunService(DemoAccessPolicy access, DemoSessionRepository sessions, RunQueryService query) {
        this.access = access;
        this.sessions = sessions;
        this.query = query;
    }

    public RunViews.PublicRunList list(String visitorOwner, String sessionId) {
        requireVisible(visitorOwner, sessionId);
        return query.listPublic(sessionId);
    }

    public RunViews.PublicDetail find(String visitorOwner, String runId) {
        RunSummary run = query.requireRun(runId);
        if (run.origin() != RunOrigin.DEMO) {
            throw missing();
        }
        requireVisible(visitorOwner, run.sessionId());
        return query.detailPublic(run);
    }

    private void requireVisible(String visitorOwner, String sessionId) {
        String id = canonicalId(sessionId);
        if (id == null || visitorOwner == null) {
            throw missing();
        }
        if (sessions.find(id, access.agentId(), visitorOwner) != null) {
            return;
        }
        if (access.isShowcase(id) && sessions.findShowcase(id, access.agentId()) != null) {
            return;
        }
        throw missing();
    }

    private static String canonicalId(String id) {
        try {
            return id != null && UUID.fromString(id).toString().equalsIgnoreCase(id)
                    ? UUID.fromString(id).toString() : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static DemoException missing() {
        return new DemoException(404, "执行记录不存在");
    }
}