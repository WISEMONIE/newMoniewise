package com.moniewise.moniewise_backend.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Key-value store for application-level configuration.
 *
 * <p>All fee tiers, active PSP, premium pricing, and feature flags live here.
 * Change a row, cache evicts in 5 minutes — no restart needed.
 */
@Entity
@Table(
    name = "system_config",
    indexes = @Index(name = "idx_system_config_key", columnList = "config_key", unique = true)
)
public class SystemConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId()                          { return id; }
    public String getConfigKey()                 { return configKey; }
    public void setConfigKey(String configKey)   { this.configKey = configKey; }
    public String getConfigValue()               { return configValue; }
    public void setConfigValue(String v)         { this.configValue = v; }
    public String getDescription()               { return description; }
    public void setDescription(String d)         { this.description = d; }
    public LocalDateTime getUpdatedAt()          { return updatedAt; }
    public void setUpdatedAt(LocalDateTime t)    { this.updatedAt = t; }
}
