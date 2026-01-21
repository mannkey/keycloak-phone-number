package com.vymalo.keycloak.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vymalo.keycloak.model.*;
import com.vymalo.keycloak.service.PhoneAuthenticationService;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resource.RealmResourceProvider;

import java.io.IOException;

/**
 * REST API Resource Provider for Phone-based Authentication.
 * 
 * Endpoints:
 * - POST /realms/{realm}/phone-auth/request-otp
 * - POST /realms/{realm}/phone-auth/verify-otp
 * - POST /realms/{realm}/phone-auth/resend-otp
 * 
 * This provider enables headless/API-based phone authentication
 * for mobile apps, SPAs, and other non-browser clients.
 */
@JBossLog
public class PhoneAuthResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final ObjectMapper objectMapper;

    public PhoneAuthResourceProvider(KeycloakSession session) {
        this.session = session;
        this.realm = session.getContext().getRealm();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Object getResource() {
        return this;
    }

    /**
     * Request OTP endpoint
     * POST /realms/{realm}/phone-auth/request-otp
     * 
     * Request body:
     * {
     *   "phone": "9876543210",
     *   "regionPrefix": "+91",
     *   "clientId": "my-mobile-app"
     * }
     * 
     * Response:
     * {
     *   "success": true,
     *   "sessionId": "uuid",
     *   "phoneNumber": "+919876543210",
     *   "expiresIn": 600
     * }
     */
    @POST
    @Path("/request-otp")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response requestOtp(String requestBody, @Context org.keycloak.http.HttpRequest httpRequest) {
        log.debug("Received request-otp call");

        try {
            // Parse request
            PhoneAuthRequest request = objectMapper.readValue(requestBody, PhoneAuthRequest.class);
            
            // Get client IP and User-Agent
            String ipAddress = getClientIpAddress(httpRequest);
            String userAgent = httpRequest.getHttpHeaders().getHeaderString("User-Agent");

            // Process request
            PhoneAuthenticationService authService = new PhoneAuthenticationService(session, realm);
            PhoneAuthResponse response = authService.requestOtp(request, ipAddress, userAgent);

            // Return response
            if (response.isSuccess()) {
                log.infof("OTP request successful for phone: %s", response.getPhoneNumber());
                return Response.ok(response).build();
            } else {
                log.warnf("OTP request failed: %s", response.getError());
                return Response.status(Response.Status.BAD_REQUEST).entity(response).build();
            }

        } catch (IOException e) {
            log.error("Failed to parse request body", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(PhoneAuthResponse.error("Invalid request format", "INVALID_REQUEST"))
                    .build();
        } catch (Exception e) {
            log.error("Internal error processing OTP request", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(PhoneAuthResponse.error("Internal server error", "INTERNAL_ERROR"))
                    .build();
        }
    }

    /**
     * Verify OTP endpoint
     * POST /realms/{realm}/phone-auth/verify-otp
     * 
     * Request body:
     * {
     *   "sessionId": "uuid",
     *   "code": "123456"
     * }
     * 
     * Response:
     * {
     *   "success": true,
     *   "accessToken": "eyJhbGci...",
     *   "refreshToken": "eyJhbGci...",
     *   "tokenType": "Bearer",
     *   "expiresIn": 300
     * }
     */
    @POST
    @Path("/verify-otp")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response verifyOtp(String requestBody, @Context org.keycloak.http.HttpRequest httpRequest) {
        log.debug("Received verify-otp call");

        try {
            // Parse request
            OtpVerifyRequest request = objectMapper.readValue(requestBody, OtpVerifyRequest.class);
            
            // Get client IP
            String ipAddress = getClientIpAddress(httpRequest);

            // Process verification
            PhoneAuthenticationService authService = new PhoneAuthenticationService(session, realm);
            OtpVerifyResponse response = authService.verifyOtp(request, ipAddress);

            // Return response
            if (response.isSuccess()) {
                log.infof("OTP verification successful for session: %s", request.getSessionId());
                return Response.ok(response).build();
            } else {
                log.warnf("OTP verification failed for session: %s - %s", 
                         request.getSessionId(), response.getError());
                
                // Use 401 for authentication failures, 400 for validation errors
                int statusCode = "INVALID_OTP".equals(response.getErrorCode()) || 
                                "SESSION_LOCKED".equals(response.getErrorCode())
                                ? Response.Status.UNAUTHORIZED.getStatusCode()
                                : Response.Status.BAD_REQUEST.getStatusCode();
                
                return Response.status(statusCode).entity(response).build();
            }

        } catch (IOException e) {
            log.error("Failed to parse request body", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(OtpVerifyResponse.error("Invalid request format", "INVALID_REQUEST", null))
                    .build();
        } catch (Exception e) {
            log.error("Internal error processing OTP verification", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(OtpVerifyResponse.error("Internal server error", "INTERNAL_ERROR", null))
                    .build();
        }
    }

    /**
     * Resend OTP endpoint
     * POST /realms/{realm}/phone-auth/resend-otp
     * 
     * Request body:
     * {
     *   "sessionId": "uuid"
     * }
     * 
     * Response:
     * {
     *   "success": true,
     *   "sessionId": "uuid",
     *   "phoneNumber": "+919876543210",
     *   "expiresIn": 600
     * }
     */
    @POST
    @Path("/resend-otp")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response resendOtp(String requestBody) {
        log.debug("Received resend-otp call");

        try {
            // Parse request (simple JSON with sessionId)
            var node = objectMapper.readTree(requestBody);
            String sessionId = node.get("sessionId").asText();

            if (sessionId == null || sessionId.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(PhoneAuthResponse.error("Session ID is required", "SESSION_ID_REQUIRED"))
                        .build();
            }

            // Process resend
            PhoneAuthenticationService authService = new PhoneAuthenticationService(session, realm);
            PhoneAuthResponse response = authService.resendOtp(sessionId);

            // Return response
            if (response.isSuccess()) {
                log.infof("OTP resent successfully for session: %s", sessionId);
                return Response.ok(response).build();
            } else {
                log.warnf("OTP resend failed for session: %s - %s", sessionId, response.getError());
                return Response.status(Response.Status.BAD_REQUEST).entity(response).build();
            }

        } catch (IOException e) {
            log.error("Failed to parse request body", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(PhoneAuthResponse.error("Invalid request format", "INVALID_REQUEST"))
                    .build();
        } catch (Exception e) {
            log.error("Internal error processing OTP resend", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(PhoneAuthResponse.error("Internal server error", "INTERNAL_ERROR"))
                    .build();
        }
    }

    /**
     * Health check endpoint
     * GET /realms/{realm}/phone-auth/health
     */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        return Response.ok()
                .entity("{\"status\":\"UP\",\"service\":\"phone-auth\"}")
                .build();
    }

    /**
     * Get supported countries endpoint
     * GET /realms/{realm}/phone-auth/countries
     */
    @GET
    @Path("/countries")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCountries() {
        try {
            var countries = com.vymalo.keycloak.services.SmsService.getAllCountries();
            return Response.ok(countries).build();
        } catch (Exception e) {
            log.error("Error fetching countries", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Failed to fetch countries\"}")
                    .build();
        }
    }

    @Override
    public void close() {
        // Cleanup if needed
    }

    // Helper methods

    private String getClientIpAddress(org.keycloak.http.HttpRequest httpRequest) {
        // Check for X-Forwarded-For header (proxy/load balancer)
        String xForwardedFor = httpRequest.getHttpHeaders().getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs, take the first one
            return xForwardedFor.split(",")[0].trim();
        }

        // Check for X-Real-IP header
        String xRealIp = httpRequest.getHttpHeaders().getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        // Fallback to a default value since getRemoteAddr() is not available
        // In production, configure proper headers from your load balancer
        return "unknown";
    }
}
