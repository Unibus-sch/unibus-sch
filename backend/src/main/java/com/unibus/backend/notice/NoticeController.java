package com.unibus.backend.notice;

import com.unibus.backend.common.api.ApiResponse;
import com.unibus.backend.common.api.ApiRequestException;
import com.unibus.backend.auth.AdminRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notices")
public class NoticeController {

    private static final Logger log = LoggerFactory.getLogger(NoticeController.class);
    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @GetMapping
    ResponseEntity<ApiResponse<?>> findAll() {
        try {
            return ResponseEntity.ok(ApiResponse.success(noticeService.findAll()));
        } catch (RuntimeException error) {
            log.error("Failed to fetch notices", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch notices"));
        }
    }

    @GetMapping("/{id}")
    ResponseEntity<ApiResponse<?>> findById(@PathVariable String id) {
        try {
            return noticeService.findByIdAndIncrementViewCount(id)
                .<ResponseEntity<ApiResponse<?>>>map(notice -> ResponseEntity.ok(ApiResponse.success(notice)))
                .orElseGet(() -> ResponseEntity.status(404).body(ApiResponse.error("Notice not found")));
        } catch (RuntimeException error) {
            log.error("Failed to fetch notice", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to fetch notice"));
        }
    }

    @PostMapping
    ResponseEntity<ApiResponse<?>> create(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(
                noticeService.create(body, AdminRequest.user(request).id())
            ));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to create notice", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to create notice"));
        }
    }

    @PutMapping("/{id}")
    ResponseEntity<ApiResponse<?>> update(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        try {
            return ResponseEntity.ok(ApiResponse.success(noticeService.update(id, body)));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to update notice", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to update notice"));
        }
    }

    @DeleteMapping("/{id}")
    ResponseEntity<?> delete(@PathVariable String id) {
        try {
            noticeService.delete(id);
            return ResponseEntity.ok(new DeleteResponse(true, "Notice deleted successfully"));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to delete notice", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to delete notice"));
        }
    }

    @PostMapping("/images")
    ResponseEntity<ApiResponse<?>> uploadImage(@RequestParam(name = "file", required = false) MultipartFile file) {
        try {
            return ResponseEntity.ok(ApiResponse.success(new ImageUpload(noticeService.uploadImage(file))));
        } catch (ApiRequestException error) {
            return ResponseEntity.status(error.status()).body(ApiResponse.error(error.getMessage()));
        } catch (RuntimeException error) {
            log.error("Failed to upload image", error);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to upload image"));
        }
    }

    private record DeleteResponse(boolean success, String message) {
    }

    private record ImageUpload(String url) {
    }
}
