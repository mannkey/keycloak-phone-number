package com.vymalo.keycloak.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Request model for initiating phone authentication
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PhoneAuthRequest {

    @JsonProperty("phone")
    @Nonnull
    private String phone;

    @JsonProperty("regionPrefix")
    @Nonnull
    private String regionPrefix;

    @JsonProperty("clientId")
    private String clientId;

    public String getFullPhoneNumber() {
        if (phone == null || regionPrefix == null) {
            return null;
        }
        // Remove leading zeros from phone number
        String cleanPhone = phone.replaceFirst("^0+", "");
        return regionPrefix + cleanPhone;
    }
}
