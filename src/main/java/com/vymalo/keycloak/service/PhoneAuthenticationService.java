package com.vymalo.keycloak.service;

import com.vymalo.keycloak.constants.ConfigKey;
import com.vymalo.keycloak.constants.PhoneNumberHelper;
import com.vymalo.keycloak.constants.Utils;
import com.vymalo.keycloak.model.*;
import com.vymalo.keycloak.services.SmsService;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.*;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.Optional;

/**
 * Core service for phone-based authentication.
 * Provides unified business logic for both browser and API flows.
 * This is the heart of the scalable architecture.
 */
@JBossLog
public class PhoneAuthenticationService {

    private static final ConcurrentHashMap<String, Object> PHONE_LOCKS = new ConcurrentHashMap<>();
    private final KeycloakSession session;
    private final RealmModel realm;
    private final SmsService smsService;
    private final PhoneAuthSessionService sessionService;

    public PhoneAuthenticationService(KeycloakSession session, RealmModel realm) {
        this.session = session;
        this.realm = realm;
        this.smsService = SmsService.getInstance();
        this.sessionService = new PhoneAuthSessionService(session);
    }

    /**
     * Initiates phone authentication by sending OTP
     */
    public PhoneAuthResponse requestOtp(PhoneAuthRequest request, String ipAddress, String userAgent) {
        try {
            // Validate request
            ValidationResult validation = validatePhoneAuthRequest(request);
            if (!validation.isValid()) {
                return PhoneAuthResponse.error(validation.getError(), validation.getErrorCode());
            }

            String fullPhoneNumber = request.getFullPhoneNumber();
            String formattedPhone = validation.getFormattedPhone();

            // Check rate limiting
            if (sessionService.isRateLimited(formattedPhone, realm.getId())) {
                log.warnf("Rate limit exceeded for phone: %s", formattedPhone);
                return PhoneAuthResponse.error(
                    "Too many requests. Please try again later.",
                    "RATE_LIMIT_EXCEEDED"
                );
            }

            // Send OTP via SMS
            Optional<String> otpHashOpt = smsService.sendSmsAndGetHash(formattedPhone);
            
            if (otpHashOpt.isEmpty()) {
                log.errorf("Failed to send SMS to: %s", formattedPhone);
                return PhoneAuthResponse.error(
                    "Failed to send OTP. Please try again.",
                    "SMS_SEND_FAILED"
                );
            }

            // Create session
            PhoneAuthSession authSession = sessionService.createSession(
                formattedPhone,
                otpHashOpt.get(),
                realm.getId(),
                request.getClientId(),
                ipAddress,
                userAgent
            );

            log.infof("OTP sent successfully to: %s, sessionId: %s", 
                     formattedPhone, authSession.getSessionId());

            return PhoneAuthResponse.success(
                authSession.getSessionId(),
                formattedPhone,
                authSession.getSecondsUntilExpiry()
            );

        } catch (Exception e) {
            log.error("Error processing OTP request", e);
            return PhoneAuthResponse.error(
                "Internal server error",
                "INTERNAL_ERROR"
            );
        }
    }

    /**
     * Verifies OTP and generates access token
     */
    public OtpVerifyResponse verifyOtp(OtpVerifyRequest request, String ipAddress) {
        try {
            // Retrieve session
            PhoneAuthSession authSession = sessionService.getSession(request.getSessionId());
            
            // Validate session
            PhoneAuthSessionService.SessionValidationResult validation = 
                sessionService.validateSessionForVerification(authSession);
            
            if (validation != PhoneAuthSessionService.SessionValidationResult.VALID) {
                return handleInvalidSession(validation, authSession);
            }

            // Verify security: check IP address hasn't changed - block if mismatch
            if (!ipAddress.equals(authSession.getIpAddress())) {
                log.warnf("IP address mismatch for session: %s. Original: %s, Current: %s",
                         authSession.getSessionId(), authSession.getIpAddress(), ipAddress);
                sessionService.recordFailedAttempt(authSession);
                return OtpVerifyResponse.error(
                    "IP address mismatch - verification blocked for security",
                    "IP_MISMATCH",
                    authSession.getMaxAttempts() - authSession.getFailedAttempts()
                );
            }

            // Validate OTP
            boolean isValid = smsService.confirmSmsCode(
                authSession.getPhoneNumber(),
                request.getCode(),
                authSession.getOtpHash()
            ).orElse(false);

            if (!isValid) {
                sessionService.recordFailedAttempt(authSession);
                int remaining = authSession.getMaxAttempts() - authSession.getFailedAttempts();
                
                log.warnf("Invalid OTP for session: %s. Remaining attempts: %d",
                         authSession.getSessionId(), remaining);
                
                return OtpVerifyResponse.error(
                    "Invalid OTP code",
                    "INVALID_OTP",
                    remaining
                );
            }

            // OTP is valid - find or create user
            UserModel user = resolveOrCreateUser(session, realm, authSession.getPhoneNumber());
            
            if (user == null || !user.isEnabled()) {
                log.errorf("User is null or disabled for phone: %s", authSession.getPhoneNumber());
                return OtpVerifyResponse.error(
                    "User account is disabled",
                    "USER_DISABLED",
                    null
                );
            }

            // Generate tokens
            TokenResult tokenResult = generateTokens(user, authSession);
            
            if (tokenResult == null) {
                log.error("Failed to generate tokens");
                return OtpVerifyResponse.error(
                    "Failed to generate authentication tokens",
                    "TOKEN_GENERATION_FAILED",
                    null
                );
            }

            // Clean up session
            sessionService.removeSession(authSession);

            log.infof("Successfully authenticated user: %s via phone: %s",
                     user.getUsername(), authSession.getPhoneNumber());

            return OtpVerifyResponse.success(
                tokenResult.getAccessToken(),
                tokenResult.getRefreshToken(),
                tokenResult.getExpiresIn()
            );

        } catch (Exception e) {
            log.error("Error verifying OTP", e);
            return OtpVerifyResponse.error(
                "Internal server error",
                "INTERNAL_ERROR",
                null
            );
        }
    }

    /**
     * Resends OTP for an existing session
     */
    public PhoneAuthResponse resendOtp(String sessionId) {
        try {
            PhoneAuthSession authSession = sessionService.getSession(sessionId);
            
            if (authSession == null) {
                return PhoneAuthResponse.error("Session not found or expired", "SESSION_NOT_FOUND");
            }

            if (!authSession.canResend()) {
                return PhoneAuthResponse.error(
                    "Maximum resend limit reached",
                    "RESEND_LIMIT_EXCEEDED"
                );
            }

            // Send new OTP
            Optional<String> newOtpHashOpt = smsService.sendSmsAndGetHash(authSession.getPhoneNumber());
            
            if (newOtpHashOpt.isEmpty()) {
                return PhoneAuthResponse.error("Failed to send OTP", "SMS_SEND_FAILED");
            }

            // Update session with new OTP hash
            boolean updated = sessionService.recordResend(authSession, newOtpHashOpt.get());
            
            if (!updated) {
                return PhoneAuthResponse.error("Failed to update session", "UPDATE_FAILED");
            }

            log.infof("OTP resent for session: %s", sessionId);

            return PhoneAuthResponse.success(
                authSession.getSessionId(),
                authSession.getPhoneNumber(),
                authSession.getSecondsUntilExpiry()
            );

        } catch (Exception e) {
            log.error("Error resending OTP", e);
            return PhoneAuthResponse.error("Internal server error", "INTERNAL_ERROR");
        }
    }

    /**
     * Verifies OTP via phone number and generates access token
     */
    public OtpVerifyResponse loginWithPhoneOtp(PhoneLoginRequest request, String ipAddress) {
        try {
            if (request == null) {
                return OtpVerifyResponse.error("Request is required", "INVALID_REQUEST", null);
            }

            if (request.getPhone() == null || request.getPhone().trim().isEmpty()) {
                return OtpVerifyResponse.error("Phone number is required", "PHONE_REQUIRED", null);
            }

            if (request.getOtp() == null || request.getOtp().trim().isEmpty()) {
                return OtpVerifyResponse.error("OTP code is required", "OTP_REQUIRED", null);
            }

            String normalizedPhone = normalizePhoneNumber(request.getPhone());
            if (normalizedPhone == null) {
                return OtpVerifyResponse.error("Invalid phone number format", "INVALID_PHONE_FORMAT", null);
            }

            PhoneAuthSession authSession = sessionService.getSessionByPhone(normalizedPhone);
            PhoneAuthSessionService.SessionValidationResult validation =
                sessionService.validateSessionForVerification(authSession);

            if (validation != PhoneAuthSessionService.SessionValidationResult.VALID) {
                return handleInvalidSession(validation, authSession);
            }

            boolean isValid = smsService.confirmSmsCode(
                authSession.getPhoneNumber(),
                request.getOtp(),
                authSession.getOtpHash()
            ).orElse(false);

            if (!isValid) {
                sessionService.recordFailedAttempt(authSession);
                int remaining = authSession.getMaxAttempts() - authSession.getFailedAttempts();

                log.warnf("Invalid OTP for phone login: %s. Remaining attempts: %d",
                    authSession.getPhoneNumber(), remaining);

                return OtpVerifyResponse.error(
                    "Invalid OTP code",
                    "INVALID_OTP",
                    remaining
                );
            }

            UserModel user = resolveOrCreateUser(session, realm, authSession.getPhoneNumber());
            if (user == null || !user.isEnabled()) {
                log.errorf("User is null or disabled for phone: %s", authSession.getPhoneNumber());
                return OtpVerifyResponse.error(
                    "User account is disabled",
                    "USER_DISABLED",
                    null
                );
            }

            TokenResult tokenResult = generateTokens(user, authSession);
            if (tokenResult == null) {
                log.error("Failed to generate tokens");
                return OtpVerifyResponse.error(
                    "Failed to generate authentication tokens",
                    "TOKEN_GENERATION_FAILED",
                    null
                );
            }

            sessionService.removeSession(authSession);

            log.infof("Successfully authenticated user: %s via phone login: %s",
                user.getUsername(), authSession.getPhoneNumber());

            return OtpVerifyResponse.success(
                tokenResult.getAccessToken(),
                tokenResult.getRefreshToken(),
                tokenResult.getExpiresIn()
            );
        } catch (Exception e) {
            log.error("Error verifying OTP via phone login", e);
            return OtpVerifyResponse.error(
                "Internal server error",
                "INTERNAL_ERROR",
                null
            );
        }
    }

    // Private helper methods

    private ValidationResult validatePhoneAuthRequest(PhoneAuthRequest request) {
        if (request == null) {
            return ValidationResult.invalid("Request is required", "INVALID_REQUEST");
        }

        if (request.getPhone() == null || request.getPhone().trim().isEmpty()) {
            return ValidationResult.invalid("Phone number is required", "PHONE_REQUIRED");
        }

        if (request.getRegionPrefix() == null || request.getRegionPrefix().trim().isEmpty()) {
            return ValidationResult.invalid("Region prefix is required", "REGION_REQUIRED");
        }

        // Validate clientId - it must be provided and exist in the realm
        if (request.getClientId() == null || request.getClientId().trim().isEmpty()) {
            return ValidationResult.invalid("Client ID is required", "CLIENT_ID_REQUIRED");
        }

        ClientModel client = realm.getClientByClientId(request.getClientId());
        if (client == null) {
            log.warnf("Invalid client ID: %s", request.getClientId());
            return ValidationResult.invalid("Invalid client ID", "INVALID_CLIENT_ID");
        }

        if (!client.isEnabled()) {
            log.warnf("Client is disabled: %s", request.getClientId());
            return ValidationResult.invalid("Client is disabled", "CLIENT_DISABLED");
        }

        String fullPhoneNumber = request.getFullPhoneNumber();
        Optional<String> formattedPhoneOpt = smsService.format(fullPhoneNumber);

        if (formattedPhoneOpt.isEmpty()) {
            log.warnf("Invalid phone number format: %s", fullPhoneNumber);
            return ValidationResult.invalid("Invalid phone number format", "INVALID_PHONE_FORMAT");
        }

        return ValidationResult.valid(formattedPhoneOpt.get());
    }

    private OtpVerifyResponse handleInvalidSession(
            PhoneAuthSessionService.SessionValidationResult validation,
            PhoneAuthSession authSession) {
        
        switch (validation) {
            case NOT_FOUND:
                return OtpVerifyResponse.error("Session not found or expired", "SESSION_NOT_FOUND", null);
            case EXPIRED:
                return OtpVerifyResponse.error("Session has expired", "SESSION_EXPIRED", null);
            case LOCKED:
                return OtpVerifyResponse.error(
                    "Session locked due to too many failed attempts",
                    "SESSION_LOCKED",
                    0
                );
            case MAX_ATTEMPTS_REACHED:
                return OtpVerifyResponse.error(
                    "Maximum verification attempts reached",
                    "MAX_ATTEMPTS_REACHED",
                    0
                );
            default:
                return OtpVerifyResponse.error("Invalid session state", "INVALID_SESSION", null);
        }
    }

    public UserModel resolveOrCreateUser(KeycloakSession session, RealmModel realm, String phoneNumber) {
        String normalizedPhone = normalizePhoneNumber(phoneNumber);
        if (normalizedPhone == null) {
            return null;
        }

        String attrName = Utils.getEnv(
            ConfigKey.USER_PHONE_ATTRIBUTE_NAME,
            PhoneNumberHelper.DEFAULT_PHONE_KEY_NAME
        );

        Object lock = PHONE_LOCKS.computeIfAbsent(normalizedPhone, key -> new Object());
        synchronized (lock) {
            UserProvider userProvider = session.users();

            // 1) Search by phone attribute
            var users = userProvider
                .searchForUserByUserAttributeStream(realm, attrName, normalizedPhone)
                .toList();

            if (!users.isEmpty()) {
                UserModel user = users.get(0);
                ensurePhoneAttribute(user, attrName, normalizedPhone);
                log.debugf("Found existing user for phone: %s", normalizedPhone);
                return user;
            }

            // 2) Fallback by username (phone-as-username)
            UserModel existingByUsername = userProvider.getUserByUsername(realm, normalizedPhone);
            if (existingByUsername != null) {
                ensurePhoneAttribute(existingByUsername, attrName, normalizedPhone);
                log.debugf("Found existing user by username for phone: %s", normalizedPhone);
                return existingByUsername;
            }

            // 3) Create new user
            UserModel newUser = userProvider.addUser(realm, normalizedPhone);
            newUser.setAttribute(attrName, Collections.singletonList(normalizedPhone));
            newUser.setEnabled(true);

            log.infof("Created new user for phone: %s", normalizedPhone);
            return newUser;
        }
    }

    private String normalizePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }

        String trimmed = phoneNumber.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        Optional<String> formatted = smsService.format(trimmed);
        if (formatted.isEmpty()) {
            log.warnf("Unable to normalize phone number: %s", trimmed);
            return trimmed;
        }

        return formatted.get();
    }

    private void ensurePhoneAttribute(UserModel user, String attrName, String phoneNumber) {
        String current = user.getFirstAttribute(attrName);
        if (current == null || current.isBlank()) {
            user.setAttribute(attrName, Collections.singletonList(phoneNumber));
        }
    }

    private TokenResult generateTokens(UserModel user, PhoneAuthSession authSession) {
        try {
            // Get the client - clientId is now validated during request, so it should exist
            ClientModel client = null;
            if (authSession.getClientId() != null && !authSession.getClientId().trim().isEmpty()) {
                client = realm.getClientByClientId(authSession.getClientId());
            }
            
            // If client not found (shouldn't happen if validation worked), fail explicitly
            if (client == null) {
                log.errorf("Client not found for token generation: %s", authSession.getClientId());
                return null;
            }

            if (!client.isEnabled()) {
                log.errorf("Client is disabled: %s", authSession.getClientId());
                return null;
            }

            // Create user session
            @SuppressWarnings("deprecation")
            UserSessionModel userSession = session.sessions().createUserSession(
                realm,
                user,
                user.getUsername(),
                authSession.getIpAddress(),
                "phone-otp",
                false,
                null,
                null
            );

            // Generate tokens using TokenManager
            org.keycloak.protocol.oidc.TokenManager tokenManager = new org.keycloak.protocol.oidc.TokenManager();
            org.keycloak.protocol.oidc.TokenManager.AccessTokenResponseBuilder responseBuilder = 
                tokenManager.responseBuilder(realm, client, null, session, userSession, null);
            
            AccessTokenResponse tokenResponse = responseBuilder
                .generateAccessToken()
                .generateRefreshToken()
                .build();

            return new TokenResult(
                tokenResponse.getToken(),
                tokenResponse.getRefreshToken(),
                (int) tokenResponse.getExpiresIn()
            );

        } catch (Exception e) {
            log.error("Error generating tokens", e);
            return null;
        }
    }

    // Helper classes

    private static class ValidationResult {
        private final boolean valid;
        private final String error;
        private final String errorCode;
        private final String formattedPhone;

        private ValidationResult(boolean valid, String error, String errorCode, String formattedPhone) {
            this.valid = valid;
            this.error = error;
            this.errorCode = errorCode;
            this.formattedPhone = formattedPhone;
        }

        static ValidationResult valid(String formattedPhone) {
            return new ValidationResult(true, null, null, formattedPhone);
        }

        static ValidationResult invalid(String error, String errorCode) {
            return new ValidationResult(false, error, errorCode, null);
        }

        boolean isValid() { return valid; }
        String getError() { return error; }
        String getErrorCode() { return errorCode; }
        String getFormattedPhone() { return formattedPhone; }
    }

    private static class TokenResult {
        private final String accessToken;
        private final String refreshToken;
        private final int expiresIn;

        TokenResult(String accessToken, String refreshToken, int expiresIn) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
        }

        String getAccessToken() { return accessToken; }
        String getRefreshToken() { return refreshToken; }
        int getExpiresIn() { return expiresIn; }
    }
}
