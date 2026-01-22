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

        String trimmedPhone = phone.trim();
        if (trimmedPhone.isEmpty()) {
            return null;
        }

        // Keep only digits so prefix comparisons work reliably
        String cleanedDigits = trimmedPhone.replaceAll("[^0-9]", "");
        String withoutLeadingZeros = cleanedDigits.replaceFirst("^0+", "");

        String regionDigits = regionPrefix.replaceAll("[^0-9]", "");
        if (!regionDigits.isEmpty() && withoutLeadingZeros.startsWith(regionDigits)) {
            withoutLeadingZeros = withoutLeadingZeros.substring(regionDigits.length());
        }

        return regionPrefix + withoutLeadingZeros;
    }
}
