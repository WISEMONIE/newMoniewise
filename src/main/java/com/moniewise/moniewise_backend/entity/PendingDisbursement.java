package com.moniewise.moniewise_backend.entity;

import com.moniewise.moniewise_backend.enums.Status;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "pending_disbursements")
public class PendingDisbursement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long envelopeId;
    private Long userId;
    private String envelopeName;
    private BigDecimal amount;

    private LocalDateTime maturedAt;
    private LocalDateTime expiresAt;
    private boolean withdrawn = false;
    private boolean refunded = false;
    private boolean notifiedUser = false;
    private boolean remindedUser = false;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private LocalDateTime processedAt;

    @Version
    private Long version; // Optimistic locking
}