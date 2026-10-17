package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentContentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.function.Predicate;

public interface DocumentFacadeService {
    GetDocumentsResponse getDocuments();

    GetDocumentsResponse getDocumentsByKbId(String kbId);

    GetDocumentContentResponse getDocumentContent(String documentId);

    /** Evaluate the server-side KB policy on the loaded document before accessing its file. */
    GetDocumentContentResponse getDocumentContent(String documentId, Predicate<String> mayReadKb);

    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    /** 上传并登记导入任务；{@code actorUserId} 必须来自服务端认证上下文，用于后台复核账号状态。 */
    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file, String actorUserId);

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);
}
