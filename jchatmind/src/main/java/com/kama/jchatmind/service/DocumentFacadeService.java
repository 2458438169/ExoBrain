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


    /**
     * 只判定「这条文档是否允许当前访问者读取」，不读文件。
     *
     * <p>导入状态这类元数据读取也要用同一条 KB 策略：游客能看公开文档的导入状态，看不到私有文档。</p>
     *
     * @throws com.kama.jchatmind.exception.DocumentContentException 文档不存在或不允许读取（按 404 处理）
     */
    void requireReadableDocument(String documentId, Predicate<String> mayReadKb);
    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    /** 上传并登记导入任务；{@code actorUserId} 必须来自服务端认证上下文，用于后台复核账号状态。 */
    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file, String actorUserId);

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);
}
