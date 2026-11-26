package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.BadgeAwardSourceType;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_badges", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "badge_id", "source_type", "source_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserBadge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "badge_id", nullable = false)
    private Badge badge;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private BadgeAwardSourceType sourceType = BadgeAwardSourceType.LEGACY;

    @Column(name = "source_id", nullable = false)
    private Long sourceId = 0L;

    @Column
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "share_title")
    private String shareTitle;

    @Column(name = "share_message", columnDefinition = "TEXT")
    private String shareMessage;

    @Column(name = "earned_at", updatable = false)
    private Instant earnedAt = Instant.now();

    @Column(name = "seen_at")
    private Instant seenAt;
}
