package com.moniewise.moniewise_backend.dto.response;

import com.moniewise.moniewise_backend.entity.BlogMedia;
import com.moniewise.moniewise_backend.entity.BlogPost;

import java.time.LocalDateTime;
import java.util.List;

public record BlogPostResponse(
        Long id,
        String title,
        String slug,
        String content,
        String excerpt,
        String coverImageUrl,
        boolean published,
        String authorName,
        List<MediaItem> media,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime publishedAt
) {

    public record MediaItem(
            Long id,
            String mediaType,
            String url,
            String fileName,
            Long fileSize,
            String contentType,
            int sortOrder
    ) {
        public static MediaItem from(BlogMedia m) {
            return new MediaItem(
                    m.getId(), m.getMediaType(), m.getUrl(),
                    m.getFileName(), m.getFileSize(), m.getContentType(),
                    m.getSortOrder()
            );
        }
    }

    public static BlogPostResponse from(BlogPost post) {
        String authorName = null;
        if (post.getAuthor() != null && post.getAuthor().getProfileData() != null) {
            Object name = post.getAuthor().getProfileData().get("name");
            if (name != null) authorName = name.toString();
        }

        List<MediaItem> mediaItems = post.getMedia() != null
                ? post.getMedia().stream().map(MediaItem::from).toList()
                : List.of();

        return new BlogPostResponse(
                post.getId(), post.getTitle(), post.getSlug(),
                post.getContent(), post.getExcerpt(), post.getCoverImageUrl(),
                post.isPublished(), authorName, mediaItems,
                post.getCreatedAt(), post.getUpdatedAt(), post.getPublishedAt()
        );
    }
}
