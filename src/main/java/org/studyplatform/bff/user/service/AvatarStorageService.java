package org.studyplatform.bff.user.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AvatarStorageService {

    public static final long MAX_AVATAR_BYTES = 5L * 1024L * 1024L;
    public static final String PUBLIC_PATH_PREFIX = "/api/v1/avatar-files/";

    private static final Map<String, String> EXTENSIONS_BY_CONTENT_TYPE = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private static final Map<String, String> CONTENT_TYPES_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "gif", "image/gif"
    );

    private final Path storageDirectory;

    public AvatarStorageService(@Value("${bff.avatar.storage-dir:./data/avatars}") String storageDirectory) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    public StoredAvatar store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "Avatar file is required");
        }

        String extension = EXTENSIONS_BY_CONTENT_TYPE.get(normalizeContentType(file.getContentType()));
        if (extension == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported avatar media type");
        }

        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "Avatar file is larger than 5 MB");
        }

        try {
            Files.createDirectories(storageDirectory);
            String fileName = userId + "-" + UUID.randomUUID() + "." + extension;
            Path destination = storageDirectory.resolve(fileName).normalize();
            if (!destination.startsWith(storageDirectory)) {
                throw new ResponseStatusException(BAD_REQUEST, "Invalid avatar file name");
            }
            file.transferTo(destination);
            return new StoredAvatar(fileName, PUBLIC_PATH_PREFIX + fileName);
        } catch (IOException ex) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Could not store avatar file");
        }
    }

    public Resource load(String fileName) {
        String safeFileName = StringUtils.cleanPath(fileName);
        if (safeFileName.contains("..")) {
            throw new ResponseStatusException(NOT_FOUND, "Avatar file not found");
        }

        Path path = storageDirectory.resolve(safeFileName).normalize();
        if (!path.startsWith(storageDirectory) || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(NOT_FOUND, "Avatar file not found");
        }

        try {
            return new UrlResource(path.toUri());
        } catch (MalformedURLException ex) {
            throw new ResponseStatusException(NOT_FOUND, "Avatar file not found");
        }
    }

    public String contentType(String fileName) {
        String extension = extensionOf(fileName).orElse("");
        return CONTENT_TYPES_BY_EXTENSION.getOrDefault(extension, "application/octet-stream");
    }

    public void deleteStoredAvatarByUrl(String avatarUrl) {
        storedFileNameFromUrl(avatarUrl).ifPresent(this::deleteStoredAvatar);
    }

    public void deleteStoredAvatar(String fileName) {
        try {
            Files.deleteIfExists(storageDirectory.resolve(fileName).normalize());
        } catch (IOException ignored) {
        }
    }

    private Optional<String> storedFileNameFromUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return Optional.empty();
        }
        int index = avatarUrl.indexOf(PUBLIC_PATH_PREFIX);
        if (index < 0) {
            return Optional.empty();
        }
        String fileName = avatarUrl.substring(index + PUBLIC_PATH_PREFIX.length());
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            return Optional.empty();
        }
        return Optional.of(fileName);
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    }

    private Optional<String> extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(fileName.substring(index + 1).toLowerCase(Locale.ROOT));
    }

    public record StoredAvatar(String fileName, String publicPath) {
    }
}
