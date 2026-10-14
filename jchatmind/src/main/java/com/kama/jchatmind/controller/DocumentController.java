package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentContentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.auth.AuthContext;
import com.kama.jchatmind.auth.ResourceAccess;
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

    // 创建文档（仅创建记录，不上传文件）
    @PostMapping("/documents")
    public ApiResponse<CreateDocumentResponse> createDocument(@RequestBody CreateDocumentRequest request) {
        AuthContext.requireAdmin();
        access.requireKb(request.getKbId(), true);
        return ApiResponse.success(documentFacadeService.createDocument(request));
    }

    // 上传文档（上传文件并创建记录）
    @PostMapping("/documents/upload")
    public ApiResponse<CreateDocumentResponse> uploadDocument(
            @RequestParam("kbId") String kbId,
            @RequestParam("file") MultipartFile file) {
        access.requireKb(kbId, true);
        return ApiResponse.success(documentFacadeService.uploadDocument(kbId, file));
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
