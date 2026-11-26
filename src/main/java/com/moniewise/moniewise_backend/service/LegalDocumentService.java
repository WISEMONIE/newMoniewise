package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.AcceptLegalDocumentResponse;
import com.moniewise.moniewise_backend.dto.response.LegalAcceptanceStatusResponse;
import com.moniewise.moniewise_backend.dto.response.LegalDocumentResponse;
import com.moniewise.moniewise_backend.entity.LegalDocument;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserLegalAcceptance;
import com.moniewise.moniewise_backend.enums.LegalDocumentType;
import com.moniewise.moniewise_backend.exception.BadRequestException;
import com.moniewise.moniewise_backend.exception.ResourceNotFoundException;
import com.moniewise.moniewise_backend.repository.LegalDocumentRepository;
import com.moniewise.moniewise_backend.repository.UserLegalAcceptanceRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class LegalDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(LegalDocumentService.class);

    /** Active legal docs change rarely, so cache them in Redis for quick reads.
     *  Key: {@code legal:active:{DOC_TYPE}}. Refreshed on publish, else via TTL. */
    private static final String CACHE_KEY_PREFIX = "legal:active:";
    private static final long CACHE_TTL_HOURS = 24L;

    private final LegalDocumentRepository legalDocumentRepository;
    private final UserLegalAcceptanceRepository acceptanceRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public LegalDocumentService(
            LegalDocumentRepository legalDocumentRepository,
            UserLegalAcceptanceRepository acceptanceRepository,
            UserRepository userRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.legalDocumentRepository = legalDocumentRepository;
        this.acceptanceRepository = acceptanceRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public LegalDocumentResponse getActiveDocument(LegalDocumentType docType) {
        LegalDocumentResponse cached = readFromCache(docType);
        if (cached != null) {
            return cached;
        }

        LegalDocument document = legalDocumentRepository
                .findFirstByDocTypeAndActiveTrueOrderByEffectiveAtDesc(docType)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Active legal document not found: " + docType
                ));

        LegalDocumentResponse response = mapToResponse(document);
        writeToCache(docType, response);
        return response;
    }

    public LegalAcceptanceStatusResponse getAcceptanceStatus(
            Long userId,
            LegalDocumentType docType
    ) {
        LegalDocument activeDocument = legalDocumentRepository
                .findFirstByDocTypeAndActiveTrueOrderByEffectiveAtDesc(docType)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Active legal document not found: " + docType
                ));

        boolean acceptedLatest = acceptanceRepository.existsByUserIdAndDocTypeAndVersion(
                userId,
                docType,
                activeDocument.getVersion()
        );

        return new LegalAcceptanceStatusResponse(
                userId,
                docType,
                activeDocument.getVersion(),
                acceptedLatest
        );
    }

    @Transactional
    public AcceptLegalDocumentResponse acceptDocument(
            Long userId,
            Long legalDocumentId,
            LegalDocumentType docType,
            String ipAddress,
            String deviceInfo
    ) {
        LegalDocument document = legalDocumentRepository.findById(legalDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Legal document not found: " + legalDocumentId
                ));

        if (!document.getDocType().equals(docType)) {
            throw new BadRequestException("Document type does not match selected document.");
        }

        if (!document.isActive()) {
            throw new BadRequestException("You can only accept the active version of this document.");
        }

        boolean alreadyAccepted = acceptanceRepository.existsByUserIdAndDocTypeAndVersion(
                userId,
                docType,
                document.getVersion()
        );

        if (alreadyAccepted) {
            UserLegalAcceptance existingAcceptance = acceptanceRepository
                    .findFirstByUserIdAndDocTypeOrderByAcceptedAtDesc(userId, docType)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Acceptance record not found"
                    ));

            updateUserLegalAcceptanceFlag(userId, docType);

            return new AcceptLegalDocumentResponse(
                    existingAcceptance.getId(),
                    existingAcceptance.getUserId(),
                    existingAcceptance.getLegalDocument().getId(),
                    existingAcceptance.getDocType(),
                    existingAcceptance.getVersion(),
                    existingAcceptance.getAcceptedAt()
            );
        }

        UserLegalAcceptance acceptance = UserLegalAcceptance.builder()
                .userId(userId)
                .legalDocument(document)
                .docType(docType)
                .version(document.getVersion())
                .acceptedAt(LocalDateTime.now())
                .ipAddress(ipAddress)
                .deviceInfo(deviceInfo)
                .build();

        UserLegalAcceptance saved = acceptanceRepository.save(acceptance);

        updateUserLegalAcceptanceFlag(userId, docType);

        return new AcceptLegalDocumentResponse(
                saved.getId(),
                saved.getUserId(),
                saved.getLegalDocument().getId(),
                saved.getDocType(),
                saved.getVersion(),
                saved.getAcceptedAt()
        );
    }

    private LegalDocumentResponse mapToResponse(LegalDocument document) {
        return new LegalDocumentResponse(
                document.getId(),
                document.getDocType(),
                document.getTitle(),
                document.getContent(),
                document.getVersion(),
                document.isActive(),
                document.getEffectiveAt(),
                document.getUpdatedAt()
        );
    }

    @SuppressWarnings("unused")
    private void updateUserLegalAcceptanceFlag(
        Long userId,
        LegalDocumentType docType
) {
    User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(
                    "User not found: " + userId
            ));

    if (docType == LegalDocumentType.TERMS_AND_CONDITIONS) {
        user.setTncAccepted(true);
        userRepository.save(user);
    }
}

    @Transactional
public LegalDocumentResponse publishNewVersion(
        LegalDocumentType docType,
        String title,
        String content,
        String version
) {
    boolean versionExists = legalDocumentRepository
            .findByDocTypeAndVersion(docType, version)
            .isPresent();

    if (versionExists) {
        throw new BadRequestException(
                "Version " + version + " already exists for document: " + docType
        );
    }

    legalDocumentRepository
            .findFirstByDocTypeAndActiveTrueOrderByEffectiveAtDesc(docType)
            .ifPresent(existingActiveDocument -> {
                existingActiveDocument.setActive(false);
                legalDocumentRepository.save(existingActiveDocument);
            });

    LocalDateTime now = LocalDateTime.now();

    LegalDocument newDocument = LegalDocument.builder()
            .docType(docType)
            .title(title)
            .content(content.trim())
            .version(version)
            .active(true)
            .effectiveAt(now)
            .createdAt(now)
            .updatedAt(now)
            .build();

    LegalDocument savedDocument = legalDocumentRepository.save(newDocument);

    LegalDocumentResponse response = mapToResponse(savedDocument);
    writeToCache(docType, response); // refresh cache with the newly published active version
    return response;
}

    /**
     * Publishes {@code content} as a new active version only if {@code version} isn't
     * already present. Idempotent — safe to call on every startup (used by the seeder).
     */
    @Transactional
    public void seedVersionIfAbsent(
            LegalDocumentType docType, String title, String content, String version
    ) {
        if (legalDocumentRepository.findByDocTypeAndVersion(docType, version).isPresent()) {
            logger.info("Legal seed skipped — {} v{} already present.", docType, version);
            return;
        }
        publishNewVersion(docType, title, content, version);
        logger.info("Legal seed published — {} v{}.", docType, version);
    }

    // ── Redis cache helpers ─────────────────────────────────────────────────────
    private String cacheKey(LegalDocumentType docType) {
        return CACHE_KEY_PREFIX + docType.name();
    }

    private LegalDocumentResponse readFromCache(LegalDocumentType docType) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(docType));
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, LegalDocumentResponse.class);
        } catch (Exception e) {
            logger.warn("Legal cache read failed for {} — falling back to DB: {}", docType, e.getMessage());
            return null;
        }
    }

    private void writeToCache(LegalDocumentType docType, LegalDocumentResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    cacheKey(docType),
                    objectMapper.writeValueAsString(response),
                    CACHE_TTL_HOURS, TimeUnit.HOURS
            );
        } catch (Exception e) {
            logger.warn("Legal cache write failed for {}: {}", docType, e.getMessage());
        }
    }
}