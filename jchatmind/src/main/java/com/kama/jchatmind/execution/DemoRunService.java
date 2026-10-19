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
        String id = requireVisible(visitorOwner, sessionId);
        return query.listPublic(id, isOwner(visitorOwner, id));
    }

    public RunViews.PublicDetail find(String visitorOwner, String runId) {
        RunSummary run = query.requireRun(runId);
        if (run.origin() != RunOrigin.DEMO) {
            throw missing();
        }
        String sessionId = requireVisible(visitorOwner, run.sessionId());
        return query.detailPublic(run, isOwner(visitorOwner, sessionId));
    }

    /**
     * 确认可以读这个会话，返回规范化后的会话 ID。
     *
     * <p>两种允许：访客自己的会话（Cookie 哈希匹配），或已发布到
     * {@code app.demo.showcase-session-ids} 的公开精选。其余一律 404，不区分“不存在”与“无权访问”。</p>
     */
    private String requireVisible(String visitorOwner, String sessionId) {
        String id = canonicalId(sessionId);
        if (id == null || visitorOwner == null) {
            throw missing();
        }
        if (isOwner(visitorOwner, id)) {
            return id;
        }
        if (access.isShowcase(id) && sessions.findShowcase(id, access.agentId()) != null) {
            return id;
        }
        throw missing();
    }

    /**
     * 当前访客是否就是这个会话的所有者。
     *
     * <p>只有所有者才拿到触发/答案消息关联：刷新后把没有答案的失败、取消、中断运行挂回原问题需要它，
     * 而公开只读示例不能因此获得别人的会话结构。</p>
     */
    private boolean isOwner(String visitorOwner, String sessionId) {
        return visitorOwner != null && sessions.find(sessionId, access.agentId(), visitorOwner) != null;
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