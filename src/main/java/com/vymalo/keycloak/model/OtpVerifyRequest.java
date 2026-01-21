package com.vymalo.keycloak.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Request model for OTP verification
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtpVerifyRequest {

    @JsonProperty("sessionId")
    @Nonnull
    private String sessionId;

    @JsonProperty("code")
    @Nonnull
    private String code;
}
