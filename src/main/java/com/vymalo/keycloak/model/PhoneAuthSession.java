package com.vymalo.keycloak.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Represents a phone authentication session stored in Keycloak's cache.
 * This is serializable to support distributed caching across cluster nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhoneAuthSession implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Unique session identifier
     */
    private String sessionId;

    /**
     * Phone number in E.164 format (e.g., +919876543210)
     */
    private String phoneNumber;

    /**
     * Hash of phone number + OTP code for validation
     */
    private String otpHash;

    /**
     * Timestamp when the session was created
     */
    private Instant createdAt;

    /**
     * Timestamp when the session expires
     */
    private Instant expiresAt;

    /**
     * Number of failed OTP attempts
     */
    @Builder.Default
    private int failedAttempts = 0;

    /**
     * Maximum allowed attempts before locking
     */
    @Builder.Default
    private int maxAttempts = 5;

    /**
     * Whether the session is locked due to too many failed attempts
     */
    @Builder.Default
    private boolean locked = false;

    /**
     * IP address of the requester (for security audit)
     */
    private String ipAddress;

    /**
     * User agent of the requester
     */
    private String userAgent;

    /**
     * Number of times OTP was resent
     */
    @Builder.Default
    private int resendCount = 0;

    /**
     * Maximum allowed resends
     */
    @Builder.Default
    private int maxResends = 3;

    /**
     * Realm ID for multi-tenancy support
     */
    private String realmId;

    /**
     * Client ID that initiated the authentication
     */
    private String clientId;

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean canResend() {
        return resendCount < maxResends;
    }

    public boolean canAttemptValidation() {
        return !locked && failedAttempts < maxAttempts;
    }

    public void incrementFailedAttempts() {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            locked = true;
        }
    }

    public void incrementResendCount() {
        resendCount++;
    }

    public long getSecondsUntilExpiry() {
        return expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
    }
}
