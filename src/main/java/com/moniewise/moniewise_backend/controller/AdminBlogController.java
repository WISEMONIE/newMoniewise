package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.BlogPostRequest;
import com.moniewise.moniewise_backend.dto.response.BlogPostResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.security.AuthenticatedUserHolder;
import com.moniewise.moniewise_backend.service.BlogService;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/admin/blog")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBlogController {

    private final BlogService blogService;
    private final AuthenticatedUserHolder authHolder;

    public AdminBlogController(BlogService blogService,
                               AuthenticatedUserHolder authHolder) {
        this.blogService = blogService;
        this.authHolder = authHolder;
    }

    @PostMapping
    public ResponseEntity<BlogPostResponse> createPost(@Valid @RequestBody BlogPostRequest request) {
        User admin = authHolder.getUser();
        return ResponseEntity.ok(blogService.createPost(request, admin));
    }

    @PutMapping("/{postId}")
    public ResponseEntity<BlogPostResponse> updatePost(
            @PathVariable Long postId,
            @Valid @RequestBody BlogPostRequest request) {
        return ResponseEntity.ok(blogService.updatePost(postId, request));
    }

    @DeleteMapping("/{postId}")
    public ResponseEntity<Map<String, String>> deletePost(@PathVariable Long postId) {
        blogService.deletePost(postId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @GetMapping
    public ResponseEntity<Page<BlogPostResponse>> listAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(blogService.getAllPosts(page, size));
    }

    @GetMapping("/{postId}")
    public ResponseEntity<BlogPostResponse> getPost(@PathVariable Long postId) {
        return ResponseEntity.ok(blogService.getPost(postId));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(blogService.uploadStandaloneFile(file));
    }

    @PostMapping(value = "/{postId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BlogPostResponse> uploadMedia(
            @PathVariable Long postId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("mediaType") String mediaType,
            @RequestParam(value = "sortOrder", defaultValue = "0") int sortOrder) {
        return ResponseEntity.ok(blogService.uploadMedia(postId, file, mediaType, sortOrder));
    }

    @PostMapping("/{postId}/media/external")
    public ResponseEntity<BlogPostResponse> addExternalMedia(
            @PathVariable Long postId,
            @RequestParam("mediaType") String mediaType,
            @RequestParam("url") String url,
            @RequestParam(value = "sortOrder", defaultValue = "0") int sortOrder) {
        return ResponseEntity.ok(blogService.addExternalMedia(postId, mediaType, url, sortOrder));
    }

    @DeleteMapping("/media/{mediaId}")
    public ResponseEntity<Map<String, String>> deleteMedia(@PathVariable Long mediaId) {
        blogService.deleteMedia(mediaId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}
