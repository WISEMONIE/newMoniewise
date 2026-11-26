package com.moniewise.moniewise_backend.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "badges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Badge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(length = 60)
    private String category;

    @Column(nullable = false)
    private Integer threshold;

    @Column(name = "icon_url")
    private String iconUrl;

    @Column(name = "share_title")
    private String shareTitle;

    @Column(name = "share_message", columnDefinition = "TEXT")
    private String shareMessage;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();
}
