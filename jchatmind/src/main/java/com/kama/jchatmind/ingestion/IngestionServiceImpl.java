package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class IngestionServiceImpl implements IngestionService {

    private final IngestionJobRepository jobs;
    private final IngestionSettings settings;

    public IngestionServiceImpl(IngestionJobRepository jobs, IngestionSettings settings) {
        this.jobs = jobs;
        this.settings = settings;
    }

    @Override
    public String submit(String kbId, String documentId, String filePath, String sourceHash, String actorUserId) {
        return jobs.insert(kbId, documentId, filePath, sourceHash, actorUserId, settings.maxAttempts());
    }

    @Override
    public Optional<IngestionJobView> status(String documentId) {
        return jobs.findLatestForDocument(documentId).map(IngestionJobView::from);
    }

    @Override
    public Map<String, IngestionJobView> statusesByKnowledgeBase(String kbId) {
        Map<String, IngestionJobView> result = new HashMap<>();
        jobs.latestForKnowledgeBase(kbId).forEach((documentId, job) ->
                result.put(documentId, IngestionJobView.from(job)));
        return result;
    }

    @Override
    public IngestionJobView retry(String documentId) {
        IngestionJob latest = jobs.findLatestForDocument(documentId)
                .orElseThrow(() -> new BizException("该文档没有导入任务"));
        if (latest.status().isActive()) {
            // 重复点击：已经有排队/执行中的任务，直接返回它，不生成并行任务。
            return IngestionJobView.from(latest);
        }
        jobs.requeue(latest.id());
        // 无论本次 requeue 是否命中（并发重复点击时可能被对方先改），都返回当前的最终状态。
        return jobs.findLatestForDocument(documentId)
                .map(IngestionJobView::from)
                .orElseGet(() -> IngestionJobView.from(latest));
    }
}
