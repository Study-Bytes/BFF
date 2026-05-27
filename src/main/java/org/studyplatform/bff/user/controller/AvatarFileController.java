package org.studyplatform.bff.user.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.studyplatform.bff.user.service.AvatarStorageService;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/avatar-files")
public class AvatarFileController {

    private final AvatarStorageService avatarStorageService;

    public AvatarFileController(AvatarStorageService avatarStorageService) {
        this.avatarStorageService = avatarStorageService;
    }

    @GetMapping("/{fileName}")
    public ResponseEntity<Resource> getAvatar(@PathVariable String fileName) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatarStorageService.contentType(fileName)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(avatarStorageService.load(fileName));
    }
}
