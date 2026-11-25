package com.moniewise.moniewise_backend.aml;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One credit into a customer's wallet, with who sent it. Feeds the 24-hour total and the review screen. */
@Entity
@Table(name = "aml_inflows",
        uniqueConstraints = @UniqueConstraint(name = "uk_aml_inflows_user_ref", columnNames = {"user_id", "reference"}),
        indexes = @Index(name = "idx_aml_inflows_user_received", columnList = "user_id,received_at"))
@Getter
@Setter
public class InflowEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String reference;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "sender_name", length = 200)
    private String senderName;

    @Column(name = "sender_bank", length = 120)
    private String senderBank;

    @Column(name = "sender_account", length = 20)
    private String senderAccount;

    /** Sent by another Wisemonie customer. */
    @Column(nullable = false)
    private boolean internal;

    @Enumerated(EnumType.STRING)
    @Column(name = "name_check", length = 20)
    private SenderNameMatcher.Result nameCheck;

    @Column(name = "hold_id")
    private Long holdId;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;
}
