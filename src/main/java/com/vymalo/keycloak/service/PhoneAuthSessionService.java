package com.vymalo.keycloak.service;

import com.vymalo.keycloak.model.PhoneAuthSession;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Manages phone authentication sessions using Keycloak's distributed cache.
 * This service is cluster-aware and horizontally scalable.
 */
@JBossLog
public class PhoneAuthSessionService {

    private static final String CACHE_NAME = "phoneAuthSessions";
    private static final String PHONE_SESSION_KEY_PREFIX = "phoneAuthSessionByPhone:";
    private static final String RATE_LIMIT_KEY_PREFIX = "ratelimit:";
    private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_REQUESTS_PER_WINDOW = 3;

    private final KeycloakSession session;
    private final Object cache; // Using Object to avoid compile-time dependency on Infinispan

    public PhoneAuthSessionService(KeycloakSession session) {
        this.session = session;
        // Try to get Infinispan cache provider for distributed storage using reflection
        this.cache = getInfinispanCache();
        if (cache == null) {
            log.warn("Infinispan cache not available, using session attributes fallback (not distributed)");
        }
    }

    @SuppressWarnings("unchecked")
    private Object getInfinispanCache() {
        try {
            // Use reflection to avoid compile-time dependency
            Class<?> providerClass = Class.forName("org.keycloak.connections.infinispan.InfinispanConnectionProvider");
            java.lang.reflect.Method getProviderMethod = session.getClass().getMethod("getProvider", Class.class);
            Object provider = getProviderMethod.invoke(session, providerClass);
            if (provider != null) {
                java.lang.reflect.Method getCacheMethod = provider.getClass().getMethod("getCache", String.class);
                return getCacheMethod.invoke(provider, CACHE_NAME);
            }
        } catch (Exception e) {
            log.debugf("Could not access Infinispan cache: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Creates a new phone authentication session
     * Invalidates any existing session for the same phone number
     */
    public PhoneAuthSession createSession(String phoneNumber, String otpHash, String realmId, 
                                         String clientId, String ipAddress, String userAgent) {
        // Invalidate any existing session for this phone number
        PhoneAuthSession existingSession = getSessionByPhone(phoneNumber);
        if (existingSession != null) {
            log.debugf("Invalidating existing session %s for phone: %s", 
                      existingSession.getSessionId(), phoneNumber);
            removeSession(existingSession);
        }

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
     * Extends expiry by 10 minutes from current expiry time (not from now)
     */
    public boolean recordResend(PhoneAuthSession authSession, String newOtpHash) {
        if (!authSession.canResend()) {
            log.warnf("Maximum resend limit reached for session: %s", authSession.getSessionId());
            return false;
        }

        authSession.incrementResendCount();
        authSession.setOtpHash(newOtpHash);
        
        // Extend expiry time on resend: current expiry + 10 minutes (not now + 10)
        Instant currentExpiry = authSession.getExpiresAt();
        authSession.setExpiresAt(currentExpiry.plus(DEFAULT_SESSION_TIMEOUT));
        
        updateSession(authSession);
        log.debugf("OTP resent for session: %s (count: %d), new expiry: %s", 
                  authSession.getSessionId(), authSession.getResendCount(), 
                  authSession.getExpiresAt());
        
        return true;
    }

    /**
     * Checks rate limiting for a phone number
     * Uses cache TTL to automatically expire rate limit counters after RATE_LIMIT_WINDOW
     */
    public boolean isRateLimited(String phoneNumber, String realmId) {
        String rateLimitKey = RATE_LIMIT_KEY_PREFIX + realmId + ":" + phoneNumber;
        Integer requestCount = getRateLimitCount(rateLimitKey);

        if (requestCount != null && requestCount >= MAX_REQUESTS_PER_WINDOW) {
            log.warnf("Rate limit exceeded for phone: %s in realm: %s (count: %d)", 
                     phoneNumber, realmId, requestCount);
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

    // Cache abstraction methods - use Keycloak's Infinispan distributed cache
    
    @SuppressWarnings("unchecked")
    private void putSession(String key, PhoneAuthSession authSession) {
        String cacheKey = "phoneAuthSession:" + key;
        long ttlMillis = Math.max(1, Duration.between(Instant.now(), authSession.getExpiresAt()).toMillis());
        
        if (cache != null) {
            try {
                // Use distributed cache with TTL via reflection
                java.lang.reflect.Method putMethod = cache.getClass().getMethod("put", Object.class, Object.class, long.class, TimeUnit.class);
                putMethod.invoke(cache, cacheKey, authSession, ttlMillis, TimeUnit.MILLISECONDS);
                return;
            } catch (Exception e) {
                log.debugf("Failed to use Infinispan cache put: %s", e.getMessage());
            }
        }
        // Fallback to session attributes (not distributed, but better than nothing)
        log.warn("Using session attributes fallback - sessions won't persist across requests");
        this.session.setAttribute(cacheKey, authSession);
    }

    @SuppressWarnings("unchecked")
    private PhoneAuthSession getSessionFromCache(String key) {
        String cacheKey = "phoneAuthSession:" + key;
        
        if (cache != null) {
            try {
                java.lang.reflect.Method getMethod = cache.getClass().getMethod("get", Object.class);
                Object value = getMethod.invoke(cache, cacheKey);
                return value instanceof PhoneAuthSession ? (PhoneAuthSession) value : null;
            } catch (Exception e) {
                log.debugf("Failed to use Infinispan cache get: %s", e.getMessage());
            }
        }
        // Fallback to session attributes
        return (PhoneAuthSession) this.session.getAttribute(cacheKey);
    }

    @SuppressWarnings("unchecked")
    private void removeSessionFromCache(String key) {
        String cacheKey = "phoneAuthSession:" + key;
        
        if (cache != null) {
            try {
                java.lang.reflect.Method removeMethod = cache.getClass().getMethod("remove", Object.class);
                removeMethod.invoke(cache, cacheKey);
                return;
            } catch (Exception e) {
                log.debugf("Failed to use Infinispan cache remove: %s", e.getMessage());
            }
        }
        this.session.removeAttribute(cacheKey);
    }

    @SuppressWarnings("unchecked")
    private void putSessionIdByPhone(String phoneNumber, String sessionId) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return;
        }

        String cacheKey = PHONE_SESSION_KEY_PREFIX + phoneNumber;
        long ttlMillis = DEFAULT_SESSION_TIMEOUT.toMillis();
        
        if (cache != null) {
            try {
                java.lang.reflect.Method putMethod = cache.getClass().getMethod("put", Object.class, Object.class, long.class, TimeUnit.class);
                putMethod.invoke(cache, cacheKey, sessionId, ttlMillis, TimeUnit.MILLISECONDS);
                return;
            } catch (Exception e) {
                log.debugf("Failed to use Infinispan cache put: %s", e.getMessage());
            }
        }
        this.session.setAttribute(cacheKey, sessionId);
    }

    @SuppressWarnings("unchecked")
    private String getSessionIdByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }

        String cacheKey = PHONE_SESSION_KEY_PREFIX + phoneNumber;
        
        if (cache != null) {
            try {
                java.lang.reflect.Method getMethod = cache.getClass().getMethod("get", Object.class);
                Object value = getMethod.invoke(cache, cacheKey);
                return value instanceof String ? (String) value : null;
            } catch (Exception e) {
                log.debugf("Failed to use Infinispan cache get: %s", e.getMessage());
            }
        }
        Object sessionId = this.session.getAttribute(cacheKey);
        return sessionId instanceof String ? (String) sessionId : null;
    }

    @SuppressWarnings("unchecked")
    private void removeSessionByPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return;
        }

        String cacheKey = PHONE_SESSION_KEY_PREFIX + phoneNumber;
        
        if (cache != null) {
            try {
                java.lang.reflect.Method removeMethod = cache.getClass().getMethod("remove", Object.class);
                removeMethod.invoke(cache, cacheKey);
                return;
            } catch (Exception e) {
                log.debugf("Failed to use Infinispan cache remove: %s", e.getMessage());
            }
        }
        this.session.removeAttribute(cacheKey);
    }

    @SuppressWarnings("unchecked")
    private Integer getRateLimitCount(String key) {
        if (cache != null) {
            try {
                java.lang.reflect.Method getMethod = cache.getClass().getMethod("get", Object.class);
                Object value = getMethod.invoke(cache, key);
                return value instanceof Integer ? (Integer) value : null;
            } catch (Exception e) {
                log.debugf("Failed to use Infinispan cache get: %s", e.getMessage());
            }
        }
        return (Integer) this.session.getAttribute(key);
    }

    @SuppressWarnings("unchecked")
    private void incrementRateLimitCount(String key) {
        Integer count = getRateLimitCount(key);
        int newCount = (count == null ? 0 : count) + 1;
        long ttlMillis = RATE_LIMIT_WINDOW.toMillis();
        
        if (cache != null) {
            try {
                // Store with TTL - cache will auto-expire after RATE_LIMIT_WINDOW
                java.lang.reflect.Method putMethod = cache.getClass().getMethod("put", Object.class, Object.class, long.class, TimeUnit.class);
                putMethod.invoke(cache, key, newCount, ttlMillis, TimeUnit.MILLISECONDS);
                return;
            } catch (Exception e) {
                log.debugf("Failed to use Infinispan cache put: %s", e.getMessage());
            }
        }
        // Fallback: store without TTL (will persist until session ends)
        this.session.setAttribute(key, newCount);
    }
}
