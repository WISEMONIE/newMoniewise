CREATE TABLE blog_posts (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(300)  NOT NULL,
    slug            VARCHAR(350)  NOT NULL UNIQUE,
    content         TEXT          NOT NULL,
    excerpt         VARCHAR(500),
    cover_image_url TEXT,
    published       BOOLEAN       NOT NULL DEFAULT FALSE,
    author_id       BIGINT        NOT NULL REFERENCES users(id),
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP
);

CREATE TABLE blog_media (
    id              BIGSERIAL PRIMARY KEY,
    blog_post_id    BIGINT        NOT NULL REFERENCES blog_posts(id) ON DELETE CASCADE,
    media_type      VARCHAR(20)   NOT NULL,
    url             TEXT          NOT NULL,
    blob_name       TEXT,
    file_name       VARCHAR(300),
    file_size       BIGINT,
    content_type    VARCHAR(100),
    sort_order      INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_blog_posts_slug ON blog_posts(slug);
CREATE INDEX idx_blog_posts_published ON blog_posts(published, published_at DESC);
CREATE INDEX idx_blog_media_post ON blog_media(blog_post_id);
