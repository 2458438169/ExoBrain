package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.DocumentConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.exception.DocumentContentException;
import com.kama.jchatmind.mapper.DocumentMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.DocumentDTO;
import com.kama.jchatmind.model.entity.Document;
import com.kama.jchatmind.model.request.CreateDocumentRequest;
import com.kama.jchatmind.model.request.UpdateDocumentRequest;
import com.kama.jchatmind.model.response.CreateDocumentResponse;
import com.kama.jchatmind.model.response.GetDocumentContentResponse;
import com.kama.jchatmind.model.response.GetDocumentsResponse;
import com.kama.jchatmind.model.vo.DocumentVO;
import com.kama.jchatmind.retrieval.IndexCandidate;
import com.kama.jchatmind.retrieval.IndexScope;
import com.kama.jchatmind.retrieval.IndexVersion;
import com.kama.jchatmind.retrieval.LegacyTitleIndexer;
import com.kama.jchatmind.retrieval.RetrievalIndexService;
import com.kama.jchatmind.retrieval.SourceDocument;
import com.kama.jchatmind.retrieval.SourceText;
import com.kama.jchatmind.service.DocumentFacadeService;
import com.kama.jchatmind.service.DocumentStorageService;
import com.kama.jchatmind.service.MarkdownParserService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.StorageCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

@Service
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final RetrievalIndexService retrievalIndexService;
    private final LegacyTitleIndexer legacyTitleIndexer;
    private final StorageCleanupService storageCleanup;

    /** 生产装配：走“候选 → 校验 → 原子发布”，并遵循该 KB 当前的索引策略。 */
    @Autowired
    public DocumentFacadeServiceImpl(DocumentMapper documentMapper, DocumentConverter documentConverter,
                                     DocumentStorageService documentStorageService,
                                     RetrievalIndexService retrievalIndexService,
                                     LegacyTitleIndexer legacyTitleIndexer,
                                     StorageCleanupService storageCleanup) {
        this.documentMapper = documentMapper;
        this.documentConverter = documentConverter;
        this.documentStorageService = documentStorageService;
        this.retrievalIndexService = retrievalIndexService;
        this.legacyTitleIndexer = legacyTitleIndexer;
        this.storageCleanup = storageCleanup;
    }

    /**
     * 兼容构造器：不接索引版本服务，按基线 legacy-title-v1 语义写入（每个章节标题一个向量）。
     * 只用于既有基线风格测试；生产装配使用上面的构造器。
     */
    public DocumentFacadeServiceImpl(DocumentMapper documentMapper, DocumentConverter documentConverter,
                                     DocumentStorageService documentStorageService,
                                     MarkdownParserService markdownParserService, RagService ragService,
                                     ChunkBgeM3Mapper chunkBgeM3Mapper, StorageCleanupService storageCleanup) {
        this(documentMapper, documentConverter, documentStorageService, null,
                new LegacyTitleIndexer(markdownParserService, ragService, chunkBgeM3Mapper), storageCleanup);
    }

    @Override
    public GetDocumentContentResponse getDocumentContent(String documentId) {
        return getDocumentContent(documentId, kbId -> true);
    }

    @Override
    public GetDocumentContentResponse getDocumentContent(String documentId, Predicate<String> mayReadKb) {
        Objects.requireNonNull(mayReadKb, "A document access policy is required");
        String id;
        try {
            id = UUID.fromString(documentId).toString();
            if (!id.equalsIgnoreCase(documentId)) throw DocumentContentException.notFound();
        } catch (IllegalArgumentException | NullPointerException invalidId) {
            throw DocumentContentException.notFound();
        }
        Document document = documentMapper.selectById(id);
        // Authorize the very same record used for the read, before metadata parsing or file access.
        if (document == null || !id.equalsIgnoreCase(document.getId()) || !mayReadKb.test(document.getKbId())) {
            throw DocumentContentException.notFound();
        }
        String filename = document.getFilename();
        if (filename != null) {
            filename = filename.replace('\\', '/');
            filename = filename.substring(filename.lastIndexOf('/') + 1);
        }
        String filetype = getFileType(filename);
        if (!"md".equalsIgnoreCase(filetype) && !"markdown".equalsIgnoreCase(filetype)) {
            throw DocumentContentException.unsupportedType();
        }
        DocumentDTO dto;
        try {
            dto = documentConverter.toDTO(document);
        } catch (JsonProcessingException invalidMetadata) {
            throw DocumentContentException.unavailable();
        }
        if (dto.getMetadata() == null || dto.getMetadata().getFilePath() == null) {
            throw DocumentContentException.unavailable();
        }
        String content = documentStorageService.readMarkdown(document.getKbId(), id, dto.getMetadata().getFilePath());
        return new GetDocumentContentResponse(id, filename, content);
    }

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // 将 CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // 将 DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的 documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        String filePath = null;
        boolean cleanupRegistered = false;
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            filePath = documentStorageService.saveFile(kbId, documentId, file);
            // 文件不参与数据库事务，回滚时补偿删除，避免留下无法检索的上传文件。
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                String uploadedPath = filePath;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            cleanupFailedUpload(uploadedPath);
                        }
                    }
                });
                cleanupRegistered = true;
            }

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            if (documentMapper.updateById(updatedDocument) <= 0) {
                throw new BizException("保存文档路径失败");
            }

            // 如果是 Markdown 文件，构建候选索引并原子发布
            if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
                indexMarkdownDocument(kbId, documentId, filePath, originalFilename);
            } else {
                // TODO: 未来可以增加其他文件类型的处理逻辑
                log.warn("待新增处理的文件类型: {}", filetype);
            }

            log.info("文档上传完成: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);
            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (IOException | RuntimeException e) {
            if (filePath != null && !cleanupRegistered) {
                cleanupFailedUpload(filePath);
            }
            log.error("文档上传失败", e);
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException("文档上传或索引失败，请检查文件和向量服务后重试");
        }
    }

    private void cleanupFailedUpload(String filePath) {
        try {
            documentStorageService.deleteFile(filePath);
        } catch (Exception cleanupError) {
            log.warn("清理上传失败的文件时发生错误: path={}", filePath, cleanupError);
        }
    }

    @Override
    @Transactional
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // Delete relational data atomically; storage cleanup starts only after commit.
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
        storageCleanup.enqueueDocument(document.getKbId(), document.getId());
        // 删除不能留下可检索的索引：该文档的 chunk 由外键级联删除，活动版本的计数按事实重算、
        // 来源摘要置空（不保留一个已不对应任何文档集合的 hash）。文件清理仍由 StorageCleanupService 负责。
        if (retrievalIndexService != null) {
            retrievalIndexService.refreshActiveRevision(document.getKbId());
        }
    }

    /**
     * 上传路径的索引：这一阶段仍然在上传事务内同步完成解析 / Embedding / 发布
     * （长事务与重试体验的限制见 docs/development/deliveries/03.md，持久化导入由任务 06 负责），
     * 但失败边界与离线重建一致：先构建候选（切块 + Embedding，不写任何索引行），再原子发布。
     * 发布走 {@link RetrievalIndexService#publish}，它在事务内重新核对文档清单 / 源 hash / 模型绑定
     * 与活动版本；任何一步失败都会回滚，并在事务外/调用方补偿删除已保存文件。
     */
    private void indexMarkdownDocument(String kbId, String documentId, String filePath, String filename) {
        try {
            log.info("开始处理 Markdown 文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);
            Path path = documentStorageService.getFilePath(filePath);
            byte[] raw = Files.readAllBytes(path);
            // 兼容构造器（不接索引版本服务）按基线 legacy 语义写入。
            IndexScope scope = retrievalIndexService == null
                    ? IndexScope.legacy()
                    : retrievalIndexService.resolveScope(kbId);
            if (scope.indexVersion() == IndexVersion.LEGACY_TITLE_V1) {
                // 旧库不静默切换：继续按 legacy-title-v1 写（无 revision、无位置），迁移必须显式重建。
                if (legacyTitleIndexer.index(kbId, documentId, raw) == 0) {
                    throw new BizException("Markdown 文档没有可索引的章节");
                }
                return;
            }
            SourceDocument source = new SourceDocument(documentId, filename, SourceText.normalize(raw));
            IndexCandidate candidate = retrievalIndexService.prepareDocument(kbId, source);
            if (candidate.chunks().isEmpty()) {
                throw new BizException("Markdown 文档没有可索引的章节");
            }
            retrievalIndexService.publish(retrievalIndexService.embedCandidate(candidate));
            log.info("Markdown 文档索引发布完成: documentId={}, chunks={}", documentId, candidate.chunks().size());
        } catch (IOException | RuntimeException e) {
            log.error("处理 Markdown 文档失败: documentId={}", documentId, e);
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException("Markdown 文档索引失败，请检查向量服务后重试");
        }
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有的文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有 Document 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用 UpdateDocumentRequest 更新 DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的 DocumentDTO 转换回 Document 实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有的 ID、kbId 和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
