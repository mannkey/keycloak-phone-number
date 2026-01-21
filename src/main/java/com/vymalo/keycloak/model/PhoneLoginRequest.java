package com.vymalo.keycloak.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Request model for phone login (phone + OTP)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PhoneLoginRequest {

    @JsonProperty("phone")
    @Nonnull
    private String phone;

    @JsonProperty("otp")
    @Nonnull
    private String otp;
}
