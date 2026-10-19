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
import com.kama.jchatmind.ingestion.IngestionJobView;
import com.kama.jchatmind.ingestion.IngestionService;
import com.kama.jchatmind.ingestion.IngestionSettings;
import com.kama.jchatmind.ingestion.IngestionStatus;
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
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Map;
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
    /** 生产装配才有：文档记录 + 导入任务同事务提交、索引由 worker 异步发布。 */
    private final IngestionService ingestionService;
    private final IngestionSettings ingestionSettings;

    /** 生产装配：上传只落库 + 登记导入任务，索引由 {@code IngestionWorker} 异步发布。 */
    @Autowired
    public DocumentFacadeServiceImpl(DocumentMapper documentMapper, DocumentConverter documentConverter,
                                     DocumentStorageService documentStorageService,
                                     RetrievalIndexService retrievalIndexService,
                                     LegacyTitleIndexer legacyTitleIndexer,
                                     StorageCleanupService storageCleanup,
                                     IngestionService ingestionService,
                                     IngestionSettings ingestionSettings) {
        this.documentMapper = documentMapper;
        this.documentConverter = documentConverter;
        this.documentStorageService = documentStorageService;
        this.retrievalIndexService = retrievalIndexService;
        this.legacyTitleIndexer = legacyTitleIndexer;
        this.storageCleanup = storageCleanup;
        this.ingestionService = ingestionService;
        this.ingestionSettings = ingestionSettings == null ? new IngestionSettings() : ingestionSettings;
    }

    /**
     * 兼容构造器：不接导入任务服务，上传仍按任务 03 的同步语义（候选 → 校验 → 原子发布）执行。
     * 只用于既有基线风格测试与不支持 worker 的嵌入式用法。
     */
    public DocumentFacadeServiceImpl(DocumentMapper documentMapper, DocumentConverter documentConverter,
                                     DocumentStorageService documentStorageService,
                                     RetrievalIndexService retrievalIndexService,
                                     LegacyTitleIndexer legacyTitleIndexer,
                                     StorageCleanupService storageCleanup) {
        this(documentMapper, documentConverter, documentStorageService, retrievalIndexService, legacyTitleIndexer,
                storageCleanup, null, new IngestionSettings());
    }

    /** 基线构造器：不接索引版本服务，按 legacy-title-v1 语义写入（每个章节标题一个向量）。 */
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
    public void requireReadableDocument(String documentId, Predicate<String> mayReadKb) {
        Objects.requireNonNull(mayReadKb, "A document access policy is required");
        String id;
        try {
            id = UUID.fromString(documentId).toString();
            if (!id.equalsIgnoreCase(documentId)) throw DocumentContentException.notFound();
        } catch (IllegalArgumentException | NullPointerException invalidId) {
            throw DocumentContentException.notFound();
        }
        Document document = documentMapper.selectById(id);
        // 与读取原文同一条策略：授权的是查到的这条记录，不是请求里的字符串。
        if (document == null || !id.equalsIgnoreCase(document.getId()) || !mayReadKb.test(document.getKbId())) {
            throw DocumentContentException.notFound();
        }
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
        return toResponse(documentMapper.selectAll(), Map.of());
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        Map<String, IngestionJobView> statuses = ingestionService == null
                ? Map.of() : ingestionService.statusesByKnowledgeBase(kbId);
        return toResponse(documentMapper.selectByKbId(kbId), statuses);
    }

    /** 文档列表增量带上导入状态；没有任务（迁移前上传的旧文档）时字段留空，而不是编造状态。 */
    private GetDocumentsResponse toResponse(List<Document> documents, Map<String, IngestionJobView> statuses) {
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                String key = document.getId() == null ? "" : document.getId().toLowerCase(Locale.ROOT);
                IngestionJobView job = statuses.get(key);
                if (job != null) {
                    vo.setJobId(job.jobId());
                    vo.setIndexStatus(job.status());
                    vo.setIndexErrorCode(job.errorCode());
                    vo.setIndexErrorMessage(job.errorMessage());
                    vo.setChunksDone(job.chunksDone());
                    vo.setChunksTotal(job.chunksTotal());
                }
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
        return uploadDocument(kbId, file, null);
    }

    /**
     * 上传 = 保存受控文件 + 文档记录 + 导入任务，<b>同一事务</b>提交；事务内不调用解析或向量服务。
     * 未落库成功就不会返回“已受理”；文件不参与数据库事务，回滚时补偿删除，进程崩溃留下的孤儿文件
     * 由 {@code IngestionOrphanFileSweeper} 按宽限期清理。
     *
     * @param actorUserId 服务端认证得到的账号 id（不来自请求体），用于 worker 开始/发布前复核账号状态
     */
    @Override
    @Transactional
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file, String actorUserId) {
        String filePath = null;
        boolean cleanupRegistered = false;
        try {
            if (file == null || file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            if (!"md".equalsIgnoreCase(filetype) && !"markdown".equalsIgnoreCase(filetype)) {
                throw new BizException("暂不支持这种文件类型，请上传 Markdown 文档");
            }
            if (file.getSize() > ingestionSettings.maxFileBytes()) {
                throw new BizException("文件超过允许的大小");
            }
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取数据库生成的 documentId）
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

            if (documentMapper.insert(document) <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件（本地写入，受控路径 kbId/documentId/随机名）。
            filePath = documentStorageService.saveFile(kbId, documentId, file);
            // 文件不参与数据库事务：回滚时补偿删除；进程崩溃的窗口由孤儿清理兜底。
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

            if (ingestionService == null) {
                // 兼容构造器：没有 worker 时保持任务 03 的同步语义。
                indexMarkdownDocument(kbId, documentId, filePath, originalFilename);
                log.info("文档上传完成(同步索引): kbId={}, documentId={}", kbId, documentId);
                return CreateDocumentResponse.builder()
                        .documentId(documentId)
                        .indexStatus(IngestionStatus.SUCCEEDED.name())
                        .build();
            }

            String sourceHash = hashSavedFile(filePath);
            String jobId = ingestionService.submit(kbId, documentId, filePath, sourceHash, actorUserId);
            log.info("文档已受理，等待异步索引: kbId={}, documentId={}, jobId={}", kbId, documentId, jobId);
            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .jobId(jobId)
                    .indexStatus(IngestionStatus.QUEUED.name())
                    .build();
        } catch (IOException | RuntimeException e) {
            if (filePath != null && !cleanupRegistered) {
                cleanupFailedUpload(filePath);
            }
            log.error("文档上传失败", e);
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            throw new BizException("文档上传失败，请检查文件后重试");
        }
    }

    /** 源文件 hash（契约 §3 的标准化文本 SHA-256），用于 worker 与发布前比对文件是否被改动。 */
    private String hashSavedFile(String filePath) throws IOException {
        Path path = documentStorageService.getFilePath(filePath);
        return SourceText.normalize(Files.readAllBytes(path)).sha256();
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
