package com.vymalo.keycloak.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vymalo.keycloak.model.OtpVerifyResponse;
import com.vymalo.keycloak.model.PhoneLoginRequest;
import com.vymalo.keycloak.service.PhoneAuthenticationService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resource.RealmResourceProvider;

import java.io.IOException;

/**
 * REST API Resource Provider for Vymalo endpoints.
 *
 * Endpoints:
 * - POST /realms/{realm}/vymalo/phone/login
 */
@JBossLog
public class VymaloResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final ObjectMapper objectMapper;

    public VymaloResourceProvider(KeycloakSession session) {
        this.session = session;
        this.realm = session.getContext().getRealm();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Object getResource() {
        return this;
    }

    /**
     * Phone login endpoint
     * POST /realms/{realm}/vymalo/phone/login
     *
     * Request body:
     * {
     *   "phone": "+911234567890",
     *   "otp": "123456"
     * }
     */
    @POST
    @Path("/phone/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response phoneLogin(String requestBody, @Context org.keycloak.http.HttpRequest httpRequest) {
        log.debug("Received phone login call");

        try {
            PhoneLoginRequest request = objectMapper.readValue(requestBody, PhoneLoginRequest.class);
            String ipAddress = getClientIpAddress(httpRequest);

            PhoneAuthenticationService authService = new PhoneAuthenticationService(session, realm);
            OtpVerifyResponse response = authService.loginWithPhoneOtp(request, ipAddress);

            if (response.isSuccess()) {
                log.infof("Phone login successful for phone: %s", request.getPhone());
                return Response.ok(response).build();
            }

            log.warnf("Phone login failed for phone: %s - %s", request.getPhone(), response.getError());
            int statusCode = "INVALID_OTP".equals(response.getErrorCode()) ||
                    "SESSION_LOCKED".equals(response.getErrorCode())
                    ? Response.Status.UNAUTHORIZED.getStatusCode()
                    : Response.Status.BAD_REQUEST.getStatusCode();

            return Response.status(statusCode).entity(response).build();
        } catch (IOException e) {
            log.error("Failed to parse request body", e);
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(OtpVerifyResponse.error("Invalid request format", "INVALID_REQUEST", null))
                .build();
        } catch (Exception e) {
            log.error("Internal error processing phone login", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(OtpVerifyResponse.error("Internal server error", "INTERNAL_ERROR", null))
                .build();
        }
    }

    @Override
    public void close() {
        // Cleanup if needed
    }

    private String getClientIpAddress(org.keycloak.http.HttpRequest httpRequest) {
        String xForwardedFor = httpRequest.getHttpHeaders().getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = httpRequest.getHttpHeaders().getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return "unknown";
    }
}
