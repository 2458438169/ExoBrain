package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.DocumentContentException;
import com.kama.jchatmind.service.DocumentStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class DocumentStorageServiceImpl implements DocumentStorageService {

    @Value("${document.storage.base-path:./data/documents}")
    private String baseStoragePath;

    @Override
    public String readMarkdown(String kbId, String documentId, String filePath) {
        String kb = previewId(kbId);
        String document = previewId(documentId);
        try {
            // Uploaded files have exactly kbId/documentId/filename. Accept neither arbitrary
            // relative paths nor Windows drive/alternate-stream syntax on any platform.
            if (filePath == null || filePath.isBlank() || filePath.indexOf('\\') >= 0 || filePath.indexOf(':') >= 0) {
                throw DocumentContentException.unavailable();
            }
            Path relative = Paths.get(filePath);
            if (relative.isAbsolute() || relative.getNameCount() != 3
                    || !kb.equalsIgnoreCase(relative.getName(0).toString())
                    || !document.equalsIgnoreCase(relative.getName(1).toString())) {
                throw DocumentContentException.unavailable();
            }
            Path filename = relative.getName(2);
            String extension = filename.toString().toLowerCase(Locale.ROOT);
            if (!extension.endsWith(".md") && !extension.endsWith(".markdown")) {
                throw DocumentContentException.unsupportedType();
            }
            Path root = Paths.get(baseStoragePath).toRealPath();
            Path file = root.resolve(relative);
            Path documentDirectory = file.getParent();
            if (!documentDirectory.toRealPath().equals(documentDirectory)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().equals(file)) {
                throw DocumentContentException.unavailable();
            }

            try (DirectoryStream<Path> rootDirectory = Files.newDirectoryStream(root)) {
                // Linux supports relative, no-follow opens on pinned directory handles, so
                // replacing a parent with a symlink during the read cannot redirect it.
                if (rootDirectory instanceof SecureDirectoryStream<Path> secureRoot) {
                    try (SecureDirectoryStream<Path> kbDirectory = secureRoot.newDirectoryStream(
                            relative.getName(0), LinkOption.NOFOLLOW_LINKS);
                         SecureDirectoryStream<Path> directory = kbDirectory.newDirectoryStream(
                                 relative.getName(1), LinkOption.NOFOLLOW_LINKS)) {
                        if (!directory.getFileAttributeView(filename, BasicFileAttributeView.class,
                                LinkOption.NOFOLLOW_LINKS).readAttributes().isRegularFile()) {
                            throw DocumentContentException.unavailable();
                        }
                        try (SeekableByteChannel channel = directory.newByteChannel(filename,
                                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                            return readBoundedUtf8(channel);
                        }
                    }
                }
                // Providers without SecureDirectoryStream still reject symlink/junction
                // targets through the real-path checks above and a no-follow file open.
                try (SeekableByteChannel channel = Files.newByteChannel(file,
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    return readBoundedUtf8(channel);
                }
            }
        } catch (InvalidPathException | NoSuchFileException | NotDirectoryException
                 | FileSystemLoopException | SecurityException invalidFile) {
            throw DocumentContentException.unavailable();
        } catch (IOException readError) {
            throw DocumentContentException.readFailed();
        }
    }

    private static String previewId(String value) {
        try {
            String id = UUID.fromString(value).toString();
            if (!id.equalsIgnoreCase(value)) throw DocumentContentException.unavailable();
            return id;
        } catch (IllegalArgumentException | NullPointerException invalidId) {
            throw DocumentContentException.unavailable();
        }
    }

    private static String readBoundedUtf8(SeekableByteChannel channel) throws IOException {
        if (channel.size() > MAX_PREVIEW_BYTES) throw DocumentContentException.tooLarge();
        // Also bound the actual read, even if the file grows after the size check.
        byte[] bytes = Channels.newInputStream(channel).readNBytes(MAX_PREVIEW_BYTES + 1);
        if (bytes.length > MAX_PREVIEW_BYTES) throw DocumentContentException.tooLarge();
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
                && bytes[2] == (byte) 0xbf ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw DocumentContentException.invalidEncoding();
        }
    }

    @Override
    public String saveFile(String kbId, String documentId, MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传的文件为空");
        }

        // 构建文件存储路径: basePath/kbId/documentId/filename
        Path kbDir = Paths.get(baseStoragePath, kbId);
        Path documentDir = kbDir.resolve(documentId);
        
        // 确保目录存在
        Files.createDirectories(documentDir);
        
        // 生成唯一文件名（使用 UUID + 原始文件名）
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFilename = UUID.randomUUID().toString() + extension;
        
        // 保存文件
        Path targetPath = documentDir.resolve(uniqueFilename);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        
        // 返回相对路径（相对于 baseStoragePath）
        String relativePath = Paths.get(kbId, documentId, uniqueFilename).toString().replace("\\", "/");
        log.info("文件保存成功: kbId={}, documentId={}, filename={}, path={}", 
                kbId, documentId, originalFilename, relativePath);
        
        return relativePath;
    }

    @Override
    public void deleteFile(String filePath) throws IOException {
        Path fullPath = getFilePath(filePath);
        if (Files.exists(fullPath)) {
            Files.delete(fullPath);
            log.info("文件删除成功: {}", filePath);
            
            // 尝试删除空的父目录
            Path parentDir = fullPath.getParent();
            if (parentDir != null && Files.exists(parentDir)) {
                try {
                    Files.delete(parentDir);
                    log.info("目录删除成功: {}", parentDir);
                } catch (IOException e) {
                    // 目录不为空或其他原因无法删除，忽略
                    log.debug("目录删除失败（可能不为空）: {}", parentDir);
                }
            }
        } else {
            log.warn("文件不存在，跳过删除: {}", filePath);
        }
    }

    @Override
    public Path getFilePath(String filePath) {
        return Paths.get(baseStoragePath, filePath);
    }

    @Override
    public boolean fileExists(String filePath) {
        Path fullPath = getFilePath(filePath);
        return Files.exists(fullPath) && Files.isRegularFile(fullPath);
    }
}
