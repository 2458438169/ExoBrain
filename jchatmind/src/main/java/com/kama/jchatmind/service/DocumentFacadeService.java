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

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);
}
