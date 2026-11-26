package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.BlogPostResponse;
import com.moniewise.moniewise_backend.service.BlogService;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@RestController
public class PublicBlogController {

    private final BlogService blogService;
    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().build();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM yyyy");

    public PublicBlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    // ── JSON API (for Flutter / any client) ─────────────────────────

    @GetMapping("/blog/api/posts")
    public ResponseEntity<Page<BlogPostResponse>> listPublished(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(blogService.getPublishedPosts(page, size));
    }

    @GetMapping("/blog/api/posts/{slug}")
    public ResponseEntity<BlogPostResponse> getBySlug(@PathVariable String slug) {
        BlogPostResponse post = blogService.getPostBySlug(slug);
        if (!post.published()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(post);
    }

    // ── Server-rendered HTML pages ──────────────────────────────────

    @GetMapping(value = "/blog", produces = "text/html; charset=UTF-8")
    public String blogIndex(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Page<BlogPostResponse> posts = blogService.getPublishedPosts(page, size);

        StringBuilder cards = new StringBuilder();
        for (BlogPostResponse post : posts.getContent()) {
            String date = post.publishedAt() != null ? post.publishedAt().format(DATE_FMT) : "";
            String cover = post.coverImageUrl() != null
                    ? "<img src=\"" + escapeHtml(post.coverImageUrl()) + "\" alt=\"\" class=\"cover\">"
                    : "";
            String excerpt = post.excerpt() != null ? escapeHtml(post.excerpt()) : "";

            cards.append("<article class=\"card\">")
                    .append(cover)
                    .append("<div class=\"card-body\">")
                    .append("<h2><a href=\"/blog/").append(escapeHtml(post.slug())).append("\">")
                    .append(escapeHtml(post.title())).append("</a></h2>")
                    .append("<p class=\"meta\">").append(date);
            if (post.authorName() != null) {
                cards.append(" · ").append(escapeHtml(post.authorName()));
            }
            cards.append("</p>")
                    .append("<p>").append(excerpt).append("</p>")
                    .append("</div></article>");
        }

        if (posts.isEmpty()) {
            cards.append("<p class=\"empty\">No posts yet. Check back soon.</p>");
        }

        StringBuilder pagination = new StringBuilder();
        if (posts.hasPrevious()) {
            pagination.append("<a href=\"/blog?page=").append(page - 1).append("\">&larr; Newer</a> ");
        }
        if (posts.hasNext()) {
            pagination.append("<a href=\"/blog?page=").append(page + 1).append("\">Older &rarr;</a>");
        }

        return blogPageShell("Blog", cards.toString(), pagination.toString());
    }

    @GetMapping(value = "/blog/{slug}", produces = "text/html; charset=UTF-8")
    public String blogPost(@PathVariable String slug) {
        BlogPostResponse post;
        try {
            post = blogService.getPostBySlug(slug);
        } catch (IllegalArgumentException e) {
            return blogPageShell("Not Found", "<p>This post could not be found.</p>", "");
        }

        if (!post.published()) {
            return blogPageShell("Not Found", "<p>This post could not be found.</p>", "");
        }

        String date = post.publishedAt() != null ? post.publishedAt().format(DATE_FMT) : "";
        String bodyHtml = htmlRenderer.render(markdownParser.parse(post.content()));

        StringBuilder mediaHtml = new StringBuilder();
        for (BlogPostResponse.MediaItem m : post.media()) {
            switch (m.mediaType().toUpperCase()) {
                case "IMAGE", "GIF", "MEME" ->
                        mediaHtml.append("<figure><img src=\"").append(escapeHtml(m.url()))
                                .append("\" alt=\"\" loading=\"lazy\"></figure>");
                case "VIDEO" ->
                        mediaHtml.append("<figure><video src=\"").append(escapeHtml(m.url()))
                                .append("\" controls preload=\"metadata\"></video></figure>");
                case "AUDIO" ->
                        mediaHtml.append("<figure><audio src=\"").append(escapeHtml(m.url()))
                                .append("\" controls preload=\"metadata\"></audio></figure>");
                case "LINK" ->
                        mediaHtml.append("<p class=\"ext-link\"><a href=\"").append(escapeHtml(m.url()))
                                .append("\" target=\"_blank\" rel=\"noopener\">").append(escapeHtml(m.url()))
                                .append("</a></p>");
            }
        }

        String cover = post.coverImageUrl() != null
                ? "<img src=\"" + escapeHtml(post.coverImageUrl()) + "\" alt=\"\" class=\"hero-cover\">"
                : "";

        String content = "<article class=\"post\">"
                + cover
                + "<h1>" + escapeHtml(post.title()) + "</h1>"
                + "<p class=\"meta\">" + date
                + (post.authorName() != null ? " · " + escapeHtml(post.authorName()) : "")
                + "</p>"
                + "<div class=\"body\">" + bodyHtml + "</div>"
                + mediaHtml
                + "</article>"
                + "<p class=\"back\"><a href=\"/blog\">&larr; Back to blog</a></p>";

        return blogPageShell(post.title(), content, "");
    }

    // ── Template ────────────────────────────────────────────────────

    private String blogPageShell(String title, String bodyHtml, String paginationHtml) {
        return "<!doctype html>"
                + "<html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escapeHtml(title) + " — Wisemonie</title>"
                + "<style>"
                + "body{margin:0;background:#F4F8F6;color:#1E2B28;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "line-height:1.65;-webkit-text-size-adjust:100%;}"
                + "main{max-width:800px;margin:0 auto;padding:32px 20px 64px;}"
                + "header{display:flex;align-items:center;gap:12px;margin-bottom:24px;}"
                + "header img{height:40px;width:auto;}"
                + "header a{text-decoration:none;}"
                + "h1{color:#0A3A3C;font-size:28px;line-height:1.3;margin:16px 0 8px;}"
                + "h2{color:#0A3A3C;line-height:1.3;margin:0 0 4px;}"
                + "h2 a{color:inherit;text-decoration:none;}"
                + "h2 a:hover{color:#0F6E56;}"
                + "a{color:#0F6E56;}"
                + ".card{background:#fff;border:1px solid #E2ECE8;border-radius:16px;margin-bottom:20px;overflow:hidden;}"
                + ".card .cover{width:100%;max-height:300px;object-fit:cover;display:block;}"
                + ".card-body{padding:24px 28px;}"
                + ".post{background:#fff;border:1px solid #E2ECE8;border-radius:16px;padding:32px 28px;}"
                + ".hero-cover{width:100%;max-height:400px;object-fit:cover;border-radius:12px;margin-bottom:16px;}"
                + ".meta{color:#6B7C77;font-size:14px;margin:4px 0 12px;}"
                + ".body{margin:16px 0;}"
                + ".body img{max-width:100%;height:auto;border-radius:8px;}"
                + "figure{margin:16px 0;}"
                + "figure img,figure video{max-width:100%;height:auto;border-radius:8px;}"
                + "figure audio{width:100%;}"
                + ".ext-link{word-break:break-all;}"
                + ".pagination{text-align:center;margin:24px 0;}"
                + ".pagination a{margin:0 8px;}"
                + ".empty{text-align:center;color:#6B7C77;padding:40px 0;}"
                + ".back{margin-top:24px;}"
                + "footer{color:#6B7C77;font-size:13px;text-align:center;margin-top:28px;}"
                + "</style></head><body><main>"
                + "<header><a href=\"/blog\"><img src=\"/images/wisemonie-logo.png\" alt=\"Wisemonie\"></a></header>"
                + bodyHtml
                + (paginationHtml.isEmpty() ? "" : "<div class=\"pagination\">" + paginationHtml + "</div>")
                + "<footer>Wisemonie Digital Technologies Limited · RC 9578392 · "
                + "<a href=\"/privacy-policy\">Privacy Policy</a> · "
                + "<a href=\"/terms-of-use\">Terms of Use</a></footer>"
                + "</main></body></html>";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
