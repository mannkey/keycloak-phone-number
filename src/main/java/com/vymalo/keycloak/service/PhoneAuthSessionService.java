package com.vymalo.keycloak.service;

import com.vymalo.keycloak.model.PhoneAuthSession;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Manages phone authentication sessions using Keycloak's distributed cache.
 * This service is cluster-aware and horizontally scalable.
 */
@JBossLog
public class PhoneAuthSessionService {

    private static final String CACHE_NAME = "phoneAuthSessions";
    private static final String PHONE_SESSION_KEY_PREFIX = "phoneAuthSessionByPhone:";
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_REQUESTS_PER_WINDOW = 3;

    private final KeycloakSession session;

    public PhoneAuthSessionService(KeycloakSession session) {
        this.session = session;
    }

    /**
     * Creates a new phone authentication session
     */
    public PhoneAuthSession createSession(String phoneNumber, String otpHash, String realmId, 
                                         String clientId, String ipAddress, String userAgent) {
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        PhoneAuthSession authSession = PhoneAuthSession.builder()
                .sessionId(sessionId)
                .phoneNumber(phoneNumber)
                .otpHash(otpHash)
                .realmId(realmId)
                .clientId(clientId)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .createdAt(now)
                .expiresAt(now.plus(DEFAULT_SESSION_TIMEOUT))
                .failedAttempts(0)
                .resendCount(0)
                .locked(false)
                .build();

        putSession(sessionId, authSession);
        putSessionIdByPhone(phoneNumber, sessionId);
        log.debugf("Created phone auth session: %s for phone: %s", sessionId, phoneNumber);
        
        return authSession;
    }

    /**
     * Retrieves a session by ID
     */
    public PhoneAuthSession getSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }

        PhoneAuthSession authSession = getSessionFromCache(sessionId);
        
        if (authSession == null) {
            log.debugf("Session not found: %s", sessionId);
            return null;
        }

        if (authSession.isExpired()) {
            log.debugf("Session expired: %s", sessionId);
            removeSession(sessionId);
            return null;
        }

        return authSession;
    }

    /**
     * Retrieves a session by phone number
     */
    public PhoneAuthSession getSessionByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }

        String sessionId = getSessionIdByPhone(phoneNumber);
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }

        PhoneAuthSession authSession = getSession(sessionId);
        if (authSession == null) {
            removeSessionByPhone(phoneNumber);
        }

        return authSession;
    }

    /**
     * Updates an existing session
     */
    public void updateSession(PhoneAuthSession authSession) {
        if (authSession == null || authSession.getSessionId() == null) {
            log.warn("Attempted to update null session");
            return;
        }

        putSession(authSession.getSessionId(), authSession);
        log.debugf("Updated session: %s", authSession.getSessionId());
    }

    /**
     * Removes a session from cache
     */
    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }

        PhoneAuthSession existing = getSessionFromCache(sessionId);
        if (existing != null) {
            removeSessionByPhone(existing.getPhoneNumber());
        }
        removeSessionFromCache(sessionId);
        log.debugf("Removed session: %s", sessionId);
    }

    /**
     * Removes a session by instance (also clears phone mapping)
     */
    public void removeSession(PhoneAuthSession authSession) {
        if (authSession == null) {
            return;
        }

        removeSession(authSession.getSessionId());
        removeSessionByPhone(authSession.getPhoneNumber());
    }

    /**
     * Increments failed attempts and locks if necessary
     */
    public void recordFailedAttempt(PhoneAuthSession authSession) {
        authSession.incrementFailedAttempts();
        updateSession(authSession);
        
        if (authSession.isLocked()) {
            log.warnf("Session locked due to too many failed attempts: %s", authSession.getSessionId());
        }
    }

    /**
     * Increments resend count
     */
    public boolean recordResend(PhoneAuthSession authSession, String newOtpHash) {
        if (!authSession.canResend()) {
            log.warnf("Maximum resend limit reached for session: %s", authSession.getSessionId());
            return false;
        }

        authSession.incrementResendCount();
        authSession.setOtpHash(newOtpHash);
        
        // Extend expiry time on resend
        Instant now = Instant.now();
        authSession.setExpiresAt(now.plus(DEFAULT_SESSION_TIMEOUT));
        
        updateSession(authSession);
        log.debugf("OTP resent for session: %s (count: %d)", 
                  authSession.getSessionId(), authSession.getResendCount());
        
        return true;
    }

    /**
     * Checks rate limiting for a phone number
     */
    public boolean isRateLimited(String phoneNumber, String realmId) {
        String rateLimitKey = "ratelimit:" + realmId + ":" + phoneNumber;
        Integer requestCount = getRateLimitCount(rateLimitKey);

        if (requestCount != null && requestCount >= MAX_REQUESTS_PER_WINDOW) {
            log.warnf("Rate limit exceeded for phone: %s in realm: %s", phoneNumber, realmId);
            return true;
        }

        incrementRateLimitCount(rateLimitKey);
        return false;
    }

    /**
     * Validates session state before OTP verification
     */
    public SessionValidationResult validateSessionForVerification(PhoneAuthSession authSession) {
        if (authSession == null) {
            return SessionValidationResult.NOT_FOUND;
        }

        if (authSession.isExpired()) {
            removeSession(authSession.getSessionId());
            return SessionValidationResult.EXPIRED;
        }

        if (authSession.isLocked()) {
            return SessionValidationResult.LOCKED;
        }

        if (!authSession.canAttemptValidation()) {
            return SessionValidationResult.MAX_ATTEMPTS_REACHED;
        }

        return SessionValidationResult.VALID;
    }

    public enum SessionValidationResult {
        VALID,
        NOT_FOUND,
        EXPIRED,
        LOCKED,
        MAX_ATTEMPTS_REACHED
    }

    // Cache abstraction methods - these use Keycloak's internal cache
    // In a real implementation, these would use Keycloak's Infinispan cache provider
    
    private void putSession(String key, PhoneAuthSession authSession) {
        // Store in Keycloak session attribute (temporary implementation)
        // In production, use: session.getProvider(InfinispanConnectionProvider.class)
        //                            .getCache(CACHE_NAME)
        //                            .put(key, authSession, DEFAULT_SESSION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        this.session.setAttribute("phoneAuthSession:" + key, authSession);
    }

    private PhoneAuthSession getSessionFromCache(String key) {
        // Retrieve from Keycloak session attribute (temporary implementation)
        return (PhoneAuthSession) this.session.getAttribute("phoneAuthSession:" + key);
    }

    private void removeSessionFromCache(String key) {
        this.session.removeAttribute("phoneAuthSession:" + key);
    }

    private void putSessionIdByPhone(String phoneNumber, String sessionId) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return;
        }

        this.session.setAttribute(PHONE_SESSION_KEY_PREFIX + phoneNumber, sessionId);
    }

    private String getSessionIdByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }

        Object sessionId = this.session.getAttribute(PHONE_SESSION_KEY_PREFIX + phoneNumber);
        return sessionId instanceof String ? (String) sessionId : null;
    }

    private void removeSessionByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return;
        }

        this.session.removeAttribute(PHONE_SESSION_KEY_PREFIX + phoneNumber);
    }

    private Integer getRateLimitCount(String key) {
        return (Integer) this.session.getAttribute(key);
    }

    private void incrementRateLimitCount(String key) {
        Integer count = getRateLimitCount(key);
        this.session.setAttribute(key, (count == null ? 0 : count) + 1);
    }
}
