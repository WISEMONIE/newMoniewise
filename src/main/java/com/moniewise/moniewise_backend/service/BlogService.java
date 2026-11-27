package com.moniewise.moniewise_backend.service;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.firebase.cloud.StorageClient;
import com.moniewise.moniewise_backend.dto.request.BlogPostRequest;
import com.moniewise.moniewise_backend.dto.response.BlogPostResponse;
import com.moniewise.moniewise_backend.entity.BlogMedia;
import com.moniewise.moniewise_backend.entity.BlogPost;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.BlogMediaRepository;
import com.moniewise.moniewise_backend.repository.BlogPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BlogService {

    private static final Logger logger = LoggerFactory.getLogger(BlogService.class);

    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;   // 10 MB
    private static final long MAX_VIDEO_SIZE = 100 * 1024 * 1024;  // 100 MB
    private static final long MAX_AUDIO_SIZE = 20 * 1024 * 1024;   // 20 MB

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime"
    );
    private static final Set<String> ALLOWED_AUDIO_TYPES = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/ogg", "audio/aac"
    );

    private final BlogPostRepository blogPostRepository;
    private final BlogMediaRepository blogMediaRepository;

    public BlogService(BlogPostRepository blogPostRepository,
                       BlogMediaRepository blogMediaRepository) {
        this.blogPostRepository = blogPostRepository;
        this.blogMediaRepository = blogMediaRepository;
    }

    @Transactional
    public BlogPostResponse createPost(BlogPostRequest request, User author) {
        BlogPost post = new BlogPost();
        post.setTitle(request.getTitle().trim());
        post.setSlug(generateSlug(request.getTitle()));
        post.setContent(request.getContent());
        post.setExcerpt(request.getExcerpt());
        post.setCoverImageUrl(request.getCoverImageUrl());
        post.setAuthor(author);

        if (request.isPublish()) {
            post.setPublished(true);
            post.setPublishedAt(LocalDateTime.now());
        }

        blogPostRepository.save(post);
        logger.info("[Blog] Created post id={} slug={} by user={}", post.getId(), post.getSlug(), author.getId());
        return BlogPostResponse.from(post);
    }

    @Transactional
    public BlogPostResponse updatePost(Long postId, BlogPostRequest request) {
        BlogPost post = blogPostRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Blog post not found"));

        post.setTitle(request.getTitle().trim());
        post.setContent(request.getContent());
        post.setExcerpt(request.getExcerpt());
        post.setCoverImageUrl(request.getCoverImageUrl());

        if (request.isPublish() && !post.isPublished()) {
            post.setPublished(true);
            post.setPublishedAt(LocalDateTime.now());
        } else if (!request.isPublish()) {
            post.setPublished(false);
            post.setPublishedAt(null);
        }

        blogPostRepository.save(post);
        logger.info("[Blog] Updated post id={}", postId);
        return BlogPostResponse.from(post);
    }

    @Transactional
    public void deletePost(Long postId) {
        BlogPost post = blogPostRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Blog post not found"));

        for (BlogMedia media : post.getMedia()) {
            deleteFirebaseBlob(media.getBlobName());
        }

        if (post.getCoverImageUrl() != null) {
            deleteCoverImageBlob(post);
        }

        blogPostRepository.delete(post);
        logger.info("[Blog] Deleted post id={}", postId);
    }

    @Transactional(readOnly = true)
    public BlogPostResponse getPost(Long postId) {
        BlogPost post = blogPostRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Blog post not found"));
        return BlogPostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public BlogPostResponse getPostBySlug(String slug) {
        BlogPost post = blogPostRepository.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Blog post not found"));
        return BlogPostResponse.from(post);
    }

    @Transactional(readOnly = true)
    public Page<BlogPostResponse> getPublishedPosts(int page, int size) {
        return blogPostRepository
                .findByPublishedTrueOrderByPublishedAtDesc(PageRequest.of(page, size))
                .map(BlogPostResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<BlogPostResponse> getAllPosts(int page, int size) {
        return blogPostRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .map(BlogPostResponse::from);
    }

    @Transactional
    public BlogPostResponse uploadMedia(Long postId, MultipartFile file, String mediaType, int sortOrder) {
        BlogPost post = blogPostRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Blog post not found"));

        validateMedia(file, mediaType);

        String url = uploadToFirebase(file, postId, mediaType);
        String blobName = buildBlobName(file, postId, mediaType);

        BlogMedia media = new BlogMedia();
        media.setBlogPost(post);
        media.setMediaType(mediaType.toUpperCase());
        media.setUrl(url);
        media.setBlobName(blobName);
        media.setFileName(file.getOriginalFilename());
        media.setFileSize(file.getSize());
        media.setContentType(file.getContentType());
        media.setSortOrder(sortOrder);

        blogMediaRepository.save(media);
        logger.info("[Blog] Uploaded {} media for post id={}", mediaType, postId);

        return BlogPostResponse.from(blogPostRepository.findById(postId).orElseThrow());
    }

    @Transactional
    public BlogPostResponse addExternalMedia(Long postId, String mediaType, String url, int sortOrder) {
        BlogPost post = blogPostRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Blog post not found"));

        BlogMedia media = new BlogMedia();
        media.setBlogPost(post);
        media.setMediaType(mediaType.toUpperCase());
        media.setUrl(url);
        media.setSortOrder(sortOrder);

        blogMediaRepository.save(media);
        logger.info("[Blog] Added external {} link for post id={}", mediaType, postId);

        return BlogPostResponse.from(blogPostRepository.findById(postId).orElseThrow());
    }

    @Transactional
    public void deleteMedia(Long mediaId) {
        BlogMedia media = blogMediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media not found"));

        deleteFirebaseBlob(media.getBlobName());
        blogMediaRepository.delete(media);
        logger.info("[Blog] Deleted media id={}", mediaId);
    }

    /** Push pictures: JPG or PNG up to 1 MB, which Android and iOS both show reliably. */
    public static final long MAX_PUSH_IMAGE_SIZE = 1024L * 1024L;

    /**
     * Uploads a push picture: JPG, PNG or GIF, up to 1 MB, checked by its
     * bytes. For a GIF it also saves the first frame as a PNG ("stillUrl"):
     * Android notifications don't animate GIFs and Firebase only promises
     * JPG/PNG there, so Android gets the still while iPhone and the app's
     * notification list get the GIF.
     */
    public Map<String, Object> uploadPushImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose an image to upload");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!PUSH_IMAGE_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Push images must be JPG, PNG or GIF");
        }
        if (file.getSize() > MAX_PUSH_IMAGE_SIZE) {
            throw new IllegalArgumentException("Push images must be 1 MB or smaller");
        }
        // The browser's stated type can be anything; check the file itself.
        String actualType = imageTypeFromBytes(file);
        if (!contentType.equals(actualType)) {
            throw new IllegalArgumentException("Push images must be JPG, PNG or GIF");
        }
        String blobName = buildStandaloneBlobName(file).replaceFirst("^blog/images/", "push/images/");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if ("image/gif".equals(contentType)) {
            byte[] still;
            try {
                still = firstFramePng(file.getBytes());
            } catch (IOException e) {
                throw new IllegalArgumentException("That GIF could not be read. Try another file.");
            }
            if (still.length > MAX_PUSH_IMAGE_SIZE) {
                throw new IllegalArgumentException("That GIF's first frame is too large. Use a smaller GIF.");
            }
            result.put("url", uploadBlobToFirebase(file, blobName));
            result.put("stillUrl", uploadBytesToFirebase(still, "image/png", blobName + ".still.png"));
        } else {
            result.put("url", uploadBlobToFirebase(file, blobName));
        }
        result.put("contentType", contentType);
        result.put("fileSize", file.getSize());
        return result;
    }

    static final java.util.Set<String> PUSH_IMAGE_TYPES = java.util.Set.of("image/jpeg", "image/png", "image/gif");

    /** The first frame of a GIF as PNG bytes. */
    static byte[] firstFramePng(byte[] gif) throws IOException {
        java.awt.image.BufferedImage frame = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(gif));
        if (frame == null) {
            throw new IOException("Not a readable image");
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(frame, "png", out)) {
            throw new IOException("No PNG writer");
        }
        return out.toByteArray();
    }

    /** "image/png", "image/jpeg" or "image/gif" from the file's first bytes, or null. */
    static String imageTypeFromBytes(MultipartFile file) {
        byte[] head = new byte[8];
        int read;
        try (java.io.InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (java.io.IOException e) {
            return null;
        }
        if (read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) {
            return "image/png";
        }
        if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // "GIF87a" or "GIF89a"
        if (read >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
                && (head[4] == '7' || head[4] == '9') && head[5] == 'a') {
            return "image/gif";
        }
        return null;
    }

    public Map<String, Object> uploadStandaloneFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Could not determine file type");
        }
        contentType = contentType.toLowerCase();

        if (contentType.startsWith("image/")) {
            if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
                throw new IllegalArgumentException("Only JPG, PNG, WEBP, and GIF images are allowed");
            }
            if (file.getSize() > MAX_IMAGE_SIZE) {
                throw new IllegalArgumentException("Images must be 10 MB or smaller");
            }
        } else if (contentType.startsWith("video/")) {
            if (!ALLOWED_VIDEO_TYPES.contains(contentType)) {
                throw new IllegalArgumentException("Only MP4, WEBM, and MOV videos are allowed");
            }
            if (file.getSize() > MAX_VIDEO_SIZE) {
                throw new IllegalArgumentException("Videos must be 100 MB or smaller");
            }
        } else {
            throw new IllegalArgumentException("Only image and video files are allowed");
        }

        String blobName = buildStandaloneBlobName(file);
        String url = uploadBlobToFirebase(file, blobName);

        return Map.of(
                "url", url,
                "fileName", blobName.substring(blobName.lastIndexOf('/') + 1),
                "contentType", file.getContentType(),
                "fileSize", file.getSize()
        );
    }

    // ── Firebase Storage ────────────────────────────────────────────

    private String uploadToFirebase(MultipartFile file, Long postId, String mediaType) {
        return uploadBlobToFirebase(file, buildBlobName(file, postId, mediaType));
    }

    private String uploadBytesToFirebase(byte[] bytes, String contentType, String blobName) {
        Bucket bucket = StorageClient.getInstance().bucket();
        String downloadToken = UUID.randomUUID().toString();
        BlobInfo blobInfo = BlobInfo
                .newBuilder(bucket.getName(), blobName)
                .setContentType(contentType)
                .setMetadata(Map.of("firebaseStorageDownloadTokens", downloadToken))
                .build();
        bucket.getStorage().create(blobInfo, bytes);
        String encodedPath = URLEncoder.encode(blobName, StandardCharsets.UTF_8).replace("+", "%20");
        return String.format(
                "https://firebasestorage.googleapis.com/v0/b/%s/o/%s?alt=media&token=%s",
                bucket.getName(), encodedPath, downloadToken);
    }

    private String uploadBlobToFirebase(MultipartFile file, String blobName) {
        try {
            Bucket bucket = StorageClient.getInstance().bucket();

            String downloadToken = UUID.randomUUID().toString();
            BlobInfo blobInfo = BlobInfo
                    .newBuilder(bucket.getName(), blobName)
                    .setContentType(file.getContentType())
                    .setMetadata(Map.of("firebaseStorageDownloadTokens", downloadToken))
                    .build();

            bucket.getStorage().create(blobInfo, file.getBytes());

            String encodedPath = URLEncoder.encode(blobName, StandardCharsets.UTF_8).replace("+", "%20");
            return String.format(
                    "https://firebasestorage.googleapis.com/v0/b/%s/o/%s?alt=media&token=%s",
                    bucket.getName(), encodedPath, downloadToken);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload blog media to Firebase Storage: " + e.getMessage(), e);
        }
    }

    private String buildBlobName(MultipartFile file, Long postId, String mediaType) {
        String originalFilename = file.getOriginalFilename();
        String extension = "bin";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
        }
        return String.format("blog/%d/%s_%d.%s", postId, mediaType.toLowerCase(), System.currentTimeMillis(), extension);
    }

    private String buildStandaloneBlobName(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "upload.bin";
        }
        String sanitized = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.length() > 100) {
            String ext = sanitized.contains(".") ? sanitized.substring(sanitized.lastIndexOf('.')) : "";
            sanitized = sanitized.substring(0, 100 - ext.length()) + ext;
        }
        return String.format("blog/images/%d_%s", System.currentTimeMillis(), sanitized);
    }

    private void deleteFirebaseBlob(String blobName) {
        if (blobName == null || blobName.isBlank()) return;
        try {
            Bucket bucket = StorageClient.getInstance().bucket();
            Blob blob = bucket.get(blobName);
            if (blob != null) blob.delete();
        } catch (Exception e) {
            logger.error("[Blog] Failed to delete Firebase blob: {}", blobName, e);
        }
    }

    private void deleteCoverImageBlob(BlogPost post) {
        // Cover images uploaded via the media upload flow have a blob entry;
        // external URLs do not — skip those.
        post.getMedia().stream()
                .filter(m -> m.getUrl().equals(post.getCoverImageUrl()) && m.getBlobName() != null)
                .findFirst()
                .ifPresent(m -> deleteFirebaseBlob(m.getBlobName()));
    }

    // ── Validation ──────────────────────────────────────────────────

    private void validateMedia(MultipartFile file, String mediaType) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IllegalArgumentException("Could not determine file type");
        }
        contentType = contentType.toLowerCase();

        switch (mediaType.toUpperCase()) {
            case "IMAGE", "GIF", "MEME" -> {
                if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
                    throw new IllegalArgumentException("Only JPG, PNG, WEBP, and GIF images are allowed");
                }
                if (file.getSize() > MAX_IMAGE_SIZE) {
                    throw new IllegalArgumentException("Images must be 10 MB or smaller");
                }
            }
            case "VIDEO" -> {
                if (!ALLOWED_VIDEO_TYPES.contains(contentType)) {
                    throw new IllegalArgumentException("Only MP4, WEBM, and MOV videos are allowed");
                }
                if (file.getSize() > MAX_VIDEO_SIZE) {
                    throw new IllegalArgumentException("Videos must be 100 MB or smaller");
                }
            }
            case "AUDIO" -> {
                if (!ALLOWED_AUDIO_TYPES.contains(contentType)) {
                    throw new IllegalArgumentException("Only MP3, WAV, OGG, and AAC audio files are allowed");
                }
                if (file.getSize() > MAX_AUDIO_SIZE) {
                    throw new IllegalArgumentException("Audio files must be 20 MB or smaller");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported media type: " + mediaType);
        }
    }

    // ── Slug generation ─────────────────────────────────────────────

    private String generateSlug(String title) {
        String base = Normalizer.normalize(title.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");

        if (base.length() > 250) base = base.substring(0, 250);

        String slug = base;
        int counter = 1;
        while (blogPostRepository.existsBySlug(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }
}
