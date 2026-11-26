package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.EngagementNudgeCampaign;
import com.moniewise.moniewise_backend.enums.EngagementNudgeChannel;
import com.moniewise.moniewise_backend.enums.EngagementNudgeSegment;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "engagement_nudge_notifications",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_engagement_nudges_user_campaign_occasion_channel_date",
                        columnNames = {"user_id", "campaign", "occasion_key", "channel", "sent_date"}
                )
        },
        indexes = {
                @Index(name = "idx_engagement_nudges_user_sent_date", columnList = "user_id, sent_date"),
                @Index(name = "idx_engagement_nudges_user_sent_at", columnList = "user_id, sent_at"),
                @Index(name = "idx_engagement_nudges_campaign_sent_at", columnList = "campaign, sent_at")
        }
)
public class EngagementNudgeNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign", nullable = false, length = 40)
    private EngagementNudgeCampaign campaign;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment", nullable = false, length = 40)
    private EngagementNudgeSegment segment;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private EngagementNudgeChannel channel;

    @Column(name = "occasion_key", nullable = false, length = 100)
    private String occasionKey;

    @Column(name = "copy_key", nullable = false, length = 140)
    private String copyKey;

    @Column(name = "sent_date", nullable = false)
    private LocalDate sentDate;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public EngagementNudgeCampaign getCampaign() {
        return campaign;
    }

    public void setCampaign(EngagementNudgeCampaign campaign) {
        this.campaign = campaign;
    }

    public EngagementNudgeSegment getSegment() {
        return segment;
    }

    public void setSegment(EngagementNudgeSegment segment) {
        this.segment = segment;
    }

    public EngagementNudgeChannel getChannel() {
        return channel;
    }

    public void setChannel(EngagementNudgeChannel channel) {
        this.channel = channel;
    }

    public String getOccasionKey() {
        return occasionKey;
    }

    public void setOccasionKey(String occasionKey) {
        this.occasionKey = occasionKey;
    }

    public String getCopyKey() {
        return copyKey;
    }

    public void setCopyKey(String copyKey) {
        this.copyKey = copyKey;
    }

    public LocalDate getSentDate() {
        return sentDate;
    }

    public void setSentDate(LocalDate sentDate) {
        this.sentDate = sentDate;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
