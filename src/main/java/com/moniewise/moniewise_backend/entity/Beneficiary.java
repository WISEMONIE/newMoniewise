package com.moniewise.moniewise_backend.entity;

import lombok.*;
import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "beneficiaries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Beneficiary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // ME

    @ManyToOne(fetch = FetchType.EAGER) // Load their details automatically
    @JoinColumn(name = "beneficiary_user_id", nullable = false)
    private User beneficiaryUser; // THEM

    private String alias; // e.g., "Landlord"

    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); }
}