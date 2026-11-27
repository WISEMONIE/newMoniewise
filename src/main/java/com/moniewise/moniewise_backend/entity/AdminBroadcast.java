package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

/** One push or email broadcast sent from the admin site: what, to whom, by whom, and how it went. */
@Entity
@Table(name = "admin_broadcasts",
        indexes = @Index(name = "idx_admin_broadcasts_created", columnList = "created_at"),
        uniqueConstraints = @UniqueConstraint(name = "ux_admin_broadcasts_request_key", columnNames = "request_key"))
@Getter
@Setter
public class AdminBroadcast {

    public enum Status { SENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(length = 120)
    private String title;

    @Column(nullable = false, length = 2000)
    private String message;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /** The first frame of a GIF picture, for Android. Null for JPG and PNG. */
    @Column(name = "still_image_url", length = 1000)
    private String stillImageUrl;

    @Column(name = "cta_label", length = 120)
    private String ctaLabel;

    @Column(name = "cta_url", length = 500)
    private String ctaUrl;

    @Column(name = "send_push", nullable = false)
    private boolean sendPush;

    @Column(name = "send_email", nullable = false)
    private boolean sendEmail;

    /** "All users", "3 chosen users" or "Test to admin@…". */
    @Column(nullable = false, length = 200)
    private String audience;

    @Column(name = "include_test_accounts", nullable = false)
    private boolean includeTestAccounts;

    @Column(nullable = false)
    private int recipients;

    @Column(name = "push_queued", nullable = false)
    private int pushQueued;

    @Column(name = "emails_queued", nullable = false)
    private int emailsQueued;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.SENDING;

    @Column(name = "sent_by", nullable = false, length = 200)
    private String sentBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** Set by the website for each compose; a second request with the same key is not sent again. */
    @Column(name = "request_key", length = 64)
    private String requestKey;

    // ── Background job (everyone, or more than 50 people) ─────────────────
    // Everything needed to finish the send after a restart lives on this row.

    @Column(nullable = false)
    private boolean background;

    @Column(name = "all_users", nullable = false)
    private boolean allUsers;

    /** Chosen user ids, comma separated; null when sending to everyone. */
    @Column(name = "recipient_ids", columnDefinition = "TEXT")
    private String recipientIds;

    @Column(name = "email_subject", length = 160)
    private String emailSubject;

    @Column(name = "email_body", columnDefinition = "TEXT")
    private String emailBody;

    @Column(length = 60)
    private String tag;

    @Column(name = "footer_note", length = 500)
    private String footerNote;

    @Column(name = "ttl_seconds")
    private Long ttlSeconds;

    /** The last user handled. Users are sent to in id order, so a restart carries on after this one. */
    @Column(name = "cursor_user_id")
    private Long cursorUserId;

    /** The current runner's lease. A runner whose token was replaced stops. */
    @Column(name = "runner_token", length = 40)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String runnerToken;

    @Column(nullable = false)
    private int attempts;

    /** Moved on after every user; a SENDING row that stops moving is picked up again. */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
