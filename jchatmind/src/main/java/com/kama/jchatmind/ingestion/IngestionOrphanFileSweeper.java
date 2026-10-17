package com.kama.jchatmind.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * 上传崩溃窗口的孤儿文件清理：文件已经写到 {@code base/kbId/documentId/...}，但文档+任务事务没有提交。
 *
 * <p>只删除<b>能证明没有主人</b>的目录，避免误删他人文件：</p>
 * <ol>
 *   <li>两级目录名都必须是规范 UUID（否则跳过，只记录日志）；</li>
 *   <li>KB 必须仍然存在（KB 级清理属于 {@code StorageCleanupService}，这里不碰）；</li>
 *   <li>{@code document} 行不存在，并且没有任何导入任务引用该文档 id；</li>
 *   <li>目录最后修改时间早于宽限期（默认 60 分钟），避开正在上传/写入的目录；</li>
 *   <li>删除时逐层校验真实路径、拒绝符号链接与目录联接。</li>
 * </ol>
 *
 * <p>它和 {@code exobrain_storage_cleanup}（已删除资源的物理清理队列）职责不同，也不是同一个队列：
 * 这里只处理“数据库里从未存在过的”文件。</p>
 */
@Component
@Slf4j
public class IngestionOrphanFileSweeper {

    private final IngestionJobRepository jobs;
    private final IngestionSettings settings;

    @Value("${document.storage.base-path:./data/documents}")
    private String baseStoragePath;

    public IngestionOrphanFileSweeper(IngestionJobRepository jobs, IngestionSettings settings) {
        this.jobs = jobs;
        this.settings = settings;
    }

    @Scheduled(fixedDelay = 1_800_000, initialDelay = 600_000)
    public void scheduled() {
        if (!settings.orphanSweepEnabled()) {
            return;
        }
        try {
            int removed = sweepOnce();
            if (removed > 0) {
                log.info("清理上传崩溃遗留的孤儿文件目录: removed={}", removed);
            }
        } catch (RuntimeException failure) {
            log.warn("孤儿文件清理失败，下次继续: {}", failure.getClass().getSimpleName());
        }
    }

    /** 执行一次清理，返回删除的文档目录数（测试直接调用）。 */
    public int sweepOnce() {
        Path configuredRoot = Paths.get(baseStoragePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        Path root;
        try {
            root = configuredRoot.toRealPath();
        } catch (IOException unresolved) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(settings.orphanSweepGraceMinutes(), ChronoUnit.MINUTES);
        int removed = 0;
        try (DirectoryStream<Path> kbDirectories = Files.newDirectoryStream(root)) {
            for (Path kbDirectory : kbDirectories) {
                String kbId = canonicalDirectory(kbDirectory, root);
                if (kbId == null || !jobs.knowledgeBaseExists(kbId)) {
                    continue;
                }
                removed += sweepKnowledgeBase(kbDirectory, kbId, cutoff);
            }
        } catch (IOException unreadableRoot) {
            log.warn("无法读取存储根目录: {}", unreadableRoot.getClass().getSimpleName());
            return removed;
        }
        return removed;
    }

    private int sweepKnowledgeBase(Path kbDirectory, String kbId, Instant cutoff) {
        int removed = 0;
        try (DirectoryStream<Path> documents = Files.newDirectoryStream(kbDirectory)) {
            for (Path documentDirectory : documents) {
                String documentId = canonicalDirectory(documentDirectory, kbDirectory);
                if (documentId == null
                        || jobs.documentExists(kbId, documentId)
                        || jobs.hasJobForDocument(documentId)) {
                    continue;
                }
                if (lastModified(documentDirectory).isAfter(cutoff)) {
                    continue;
                }
                if (deleteDirectory(documentDirectory, kbDirectory)) {
                    removed++;
                }
            }
        } catch (IOException unreadable) {
            log.warn("无法读取知识库目录: kbId={}, reason={}", kbId, unreadable.getClass().getSimpleName());
        }
        return removed;
    }

    /** 返回规范化的 UUID 目录名；不是目录、是符号链接、或不是规范 UUID 时返回 null（保持不动）。 */
    private static String canonicalDirectory(Path directory, Path parent) {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            if (!directory.toRealPath().equals(directory) || !directory.getParent().toRealPath().equals(parent)) {
                return null;
            }
        } catch (IOException unresolved) {
            return null;
        }
        String name = directory.getFileName().toString();
        try {
            if (!UUID.fromString(name).toString().equals(name.toLowerCase(Locale.ROOT))) {
                return null;
            }
            return name;
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static Instant lastModified(Path directory) {
        try {
            return Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException unknown) {
            return Instant.now();
        }
    }

    private static boolean deleteDirectory(Path directory, Path parent) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path visited, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink() || !visited.toRealPath().equals(visited)
                            || !visited.normalize().startsWith(parent.normalize())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink()) {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visited, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.deleteIfExists(visited);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException failure) {
            log.warn("删除孤儿目录失败，下次继续: reason={}", failure.getClass().getSimpleName());
            return false;
        }
    }
}
