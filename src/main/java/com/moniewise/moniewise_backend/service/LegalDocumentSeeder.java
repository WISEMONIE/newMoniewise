package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.enums.LegalDocumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Seeds the Privacy Policy and Terms of Use from bundled Markdown resources on
 * startup. Idempotent: {@link LegalDocumentService#seedVersionIfAbsent} publishes
 * only if the given version isn't already in the DB, so this is a no-op on every
 * boot after the first.
 *
 * <p>To ship updated legal content: edit the {@code .md} resource AND bump the
 * matching version constant below — the next deploy publishes it as the new active
 * version (deactivating the previous one) and refreshes the Redis cache.
 */
@Component
public class LegalDocumentSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LegalDocumentSeeder.class);

    private static final String PRIVACY_VERSION = "2026.06";
    private static final String TERMS_VERSION = "2026.08";

    private final LegalDocumentService legalDocumentService;

    public LegalDocumentSeeder(LegalDocumentService legalDocumentService) {
        this.legalDocumentService = legalDocumentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(LegalDocumentType.PRIVACY_POLICY,
                "WiseMonie Privacy Policy", "legal/privacy_policy.md", PRIVACY_VERSION);
        seed(LegalDocumentType.TERMS_AND_CONDITIONS,
                "WiseMonie Terms of Use", "legal/terms_of_use.md", TERMS_VERSION);
    }

    private void seed(LegalDocumentType docType, String title, String resourcePath, String version) {
        try {
            String content = readResource(resourcePath);
            if (content.isBlank()) {
                logger.warn("Legal seed skipped — resource {} is empty.", resourcePath);
                return;
            }
            legalDocumentService.seedVersionIfAbsent(docType, title, content, version);
        } catch (Exception e) {
            // Never let seeding failure block application startup.
            logger.error("Legal seed failed for {} v{} ({}): {}",
                    docType, version, resourcePath, e.getMessage(), e);
        }
    }

    private String readResource(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
