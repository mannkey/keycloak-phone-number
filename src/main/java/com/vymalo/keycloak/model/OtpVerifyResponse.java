package com.vymalo.keycloak.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response model for OTP verification
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OtpVerifyResponse {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("accessToken")
    private String accessToken;

    @JsonProperty("refreshToken")
    private String refreshToken;

    @JsonProperty("tokenType")
    @Builder.Default
    private String tokenType = "Bearer";

    @JsonProperty("expiresIn")
    private Integer expiresIn;

    @JsonProperty("error")
    private String error;

    @JsonProperty("errorCode")
    private String errorCode;

    @JsonProperty("remainingAttempts")
    private Integer remainingAttempts;

    public static OtpVerifyResponse success(String accessToken, String refreshToken, int expiresIn) {
        return OtpVerifyResponse.builder()
                .success(true)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(expiresIn)
                .build();
    }

    public static OtpVerifyResponse error(String error, String errorCode, Integer remainingAttempts) {
        return OtpVerifyResponse.builder()
                .success(false)
                .error(error)
                .errorCode(errorCode)
                .remainingAttempts(remainingAttempts)
                .build();
    }
}
