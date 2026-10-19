package com.kama.jchatmind.demo;

import com.kama.jchatmind.ingestion.IngestionJobView;
import com.kama.jchatmind.ingestion.IngestionService;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.service.DocumentFacadeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游客读取公开文档的导入状态（UX-01）。
 *
 * <p>只读：重试导入仍是账号/管理员能力，游客路径不开放（网关白名单也不含 /retry）。授权沿用读取原文的
 * 同一条 KB 策略 {@code access::isPublicKb}，所以私有文档与不存在的文档都得到 404，不区分两者。</p>
 */
@RestController
public class DemoIngestionController {

    private final DocumentFacadeService documents;
    private final IngestionService ingestion;
    private final DemoAccessPolicy access;
    private final DemoVisitor visitors;

    public DemoIngestionController(DocumentFacadeService documents, IngestionService ingestion,
                                   DemoAccessPolicy access, DemoVisitor visitors) {
        this.documents = documents;
        this.ingestion = ingestion;
        this.access = access;
        this.visitors = visitors;
    }

    @GetMapping("/api/demo/documents/{documentId}/ingestion")
    public ApiResponse<IngestionJobView> status(@PathVariable String documentId,
                                                HttpServletRequest request, HttpServletResponse response) {
        visitors.owner(request, response);
        documents.requireReadableDocument(documentId, access::isPublicKb);
        return ApiResponse.success(ingestion.status(documentId)
                .orElseThrow(() -> new DemoException(404, "该文档没有导入任务")));
    }

    /** 与其它游客接口一致：错误也是 JSON，不把内部异常暴露给匿名访客。 */
    @ExceptionHandler(DemoException.class)
    public ResponseEntity<Map<String, Object>> error(DemoException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.getStatus());
        body.put("message", error.getMessage());
        body.put("data", null);
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
