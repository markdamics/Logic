package com.logic.analyzer.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "log_source")
public class LogSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType type;

    /** Local file/directory path, SFTP remote path, or full HTTP(S) URL, depending on {@link #type}. */
    private String path;

    /** SFTP only. */
    private String host;

    /** SFTP only. */
    private Integer port;

    /** SFTP only. */
    private String username;

    /**
     * SFTP only. Stored as plaintext for this initial phase - a known
     * simplification to be hardened later (encryption at rest / key-based auth).
     */
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceStatus status = SourceStatus.UNVERIFIED;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant lastCheckedAt;

    protected LogSource() {
        // JPA
    }

    public LogSource(String name, SourceType type, String path, String host, Integer port,
                      String username, String password) {
        this.name = name;
        this.type = type;
        this.path = path;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.status = SourceStatus.UNVERIFIED;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public SourceType getType() {
        return type;
    }

    public String getPath() {
        return path;
    }

    public String getHost() {
        return host;
    }

    public Integer getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public SourceStatus getStatus() {
        return status;
    }

    public void setStatus(SourceStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    /**
     * Replaces the editable fields and resets connectivity status, since a change to
     * where/how a source connects invalidates any previous test result. Password is
     * only overwritten when a new one is supplied, so editing SFTP details doesn't
     * force re-entering a still-valid credential.
     */
    public void update(String name, SourceType type, String path, String host, Integer port,
                        String username, String password) {
        this.name = name;
        this.type = type;
        this.path = path;
        this.host = host;
        this.port = port;
        this.username = username;
        if (password != null && !password.isBlank()) {
            this.password = password;
        }
        this.status = SourceStatus.UNVERIFIED;
        this.lastCheckedAt = null;
    }
}
