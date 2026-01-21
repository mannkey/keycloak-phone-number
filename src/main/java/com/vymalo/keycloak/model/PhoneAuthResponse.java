package com.vymalo.keycloak.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response model for phone authentication request
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PhoneAuthResponse {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("phoneNumber")
    private String phoneNumber;

    @JsonProperty("expiresIn")
    private Long expiresIn;

    @JsonProperty("message")
    private String message;

    @JsonProperty("error")
    private String error;

    @JsonProperty("errorCode")
    private String errorCode;

    public static PhoneAuthResponse success(String sessionId, String phoneNumber, long expiresIn) {
        return PhoneAuthResponse.builder()
                .success(true)
                .sessionId(sessionId)
                .phoneNumber(phoneNumber)
                .expiresIn(expiresIn)
                .message("OTP sent successfully")
                .build();
    }

    public static PhoneAuthResponse error(String error, String errorCode) {
        return PhoneAuthResponse.builder()
                .success(false)
                .error(error)
                .errorCode(errorCode)
                .build();
    }
}
