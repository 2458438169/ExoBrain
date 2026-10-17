package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentContentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.ingestion.IngestionJobView;
import com.kama.jchatmind.ingestion.IngestionService;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.auth.AuthContext;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.vo.DocumentVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Arrays;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class DocumentController {

    private final DocumentFacadeService documentFacadeService;
    private final IngestionService ingestionService;
    private final ResourceAccess access;

    // 查询所有文档
    @GetMapping("/documents")
    public ApiResponse<GetDocumentsResponse> getDocuments() {
        return ApiResponse.success(visible(documentFacadeService.getDocuments()));
    }

    // 根据 kbId 查询文档
    @GetMapping("/documents/kb/{kbId}")
    public ApiResponse<GetDocumentsResponse> getDocumentsByKbId(@PathVariable String kbId) {
        access.requireKb(kbId, false);
        return ApiResponse.success(visible(documentFacadeService.getDocumentsByKbId(kbId)));
    }

    @GetMapping("/documents/{documentId}/content")
    public ApiResponse<GetDocumentContentResponse> getDocumentContent(@PathVariable String documentId,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        AuthContext.requireUser();
        return ApiResponse.success(documentFacadeService.getDocumentContent(documentId, kb -> access.canRead(ResourceAccess.KB, kb)));
    }

    /**
     * 导入状态：按 KB <b>读</b>权限校验（游客/普通用户可读公开只读文档的状态），
     * 响应只含状态、真实 chunk 计数与固定错误码/文案。
     */
    @GetMapping("/documents/{documentId}/ingestion")
    public ApiResponse<IngestionJobView> getIngestionStatus(@PathVariable String documentId) {
        access.requireDocument(documentId, false);
        return ApiResponse.success(ingestionService.status(documentId)
                .orElseThrow(() -> new BizException("该文档没有导入任务")));
    }

    /**
     * 重试导入：按 KB <b>写</b>权限校验（普通用户不能重试公开只读文档，游客不开放此路径）。
     * 重复点击是幂等的：已有排队/执行中的任务时直接返回它。
     */
    @PostMapping("/documents/{documentId}/ingestion/retry")
    public ApiResponse<IngestionJobView> retryIngestion(@PathVariable String documentId) {
        access.requireDocument(documentId, true);
        return ApiResponse.success(ingestionService.retry(documentId));
    }

    // 创建文档（仅创建记录，不上传文件）
    @PostMapping("/documents")
    public ApiResponse<CreateDocumentResponse> createDocument(@RequestBody CreateDocumentRequest request) {
        AuthContext.requireAdmin();
        access.requireKb(request.getKbId(), true);
        return ApiResponse.success(documentFacadeService.createDocument(request));
    }

    // 上传文档（保存文件 + 文档记录 + 导入任务同事务提交，索引异步发布）
    @PostMapping("/documents/upload")
    public ApiResponse<CreateDocumentResponse> uploadDocument(
            @RequestParam("kbId") String kbId,
            @RequestParam("file") MultipartFile file) {
        access.requireKb(kbId, true);
        // actor 来自服务端认证上下文，后台 worker 只用它复核账号状态，不接受请求伪造的 userId。
        String actorUserId = AuthContext.requireUser().id();
        return ApiResponse.success(documentFacadeService.uploadDocument(kbId, file, actorUserId));
    }

    // 删除文档
    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String documentId) {
        access.requireDocument(documentId, true);
        documentFacadeService.deleteDocument(documentId);
        return ApiResponse.success();
    }

    // 更新文档
    @PatchMapping("/documents/{documentId}")
    public ApiResponse<Void> updateDocument(@PathVariable String documentId, @RequestBody UpdateDocumentRequest request) {
        access.requireDocument(documentId, true);
        documentFacadeService.updateDocument(documentId, request);
        return ApiResponse.success();
    }

    private GetDocumentsResponse visible(GetDocumentsResponse response) {
        response.setDocuments(Arrays.stream(response.getDocuments())
                .filter(document -> access.canRead(ResourceAccess.KB, document.getKbId()))
                .peek(document -> document.setReadOnly(!access.canWrite(ResourceAccess.KB, document.getKbId())))
                .toArray(DocumentVO[]::new));
        return response;
    }
}
