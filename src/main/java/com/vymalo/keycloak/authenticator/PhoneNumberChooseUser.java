package com.vymalo.keycloak.authenticator;

import com.vymalo.keycloak.constants.PhoneKey;
import com.vymalo.keycloak.service.PhoneAuthenticationService;
import lombok.NoArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.events.Errors;
import org.keycloak.models.AuthenticationExecutionModel;

@JBossLog
@NoArgsConstructor
public class PhoneNumberChooseUser extends AbstractPhoneNumberAuthenticator {

    public static final String PROVIDER_ID = "phone-number-choose-user";
    public static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED
    };

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        final var event = context.getEvent();
        final var authenticationSession = context.getAuthenticationSession();
        final var phoneNumber = authenticationSession.getAuthNote(PhoneKey.ATTEMPTED_PHONE_NUMBER);

        final var realm = context.getRealm();
        PhoneAuthenticationService authService = new PhoneAuthenticationService(context.getSession(), realm);
        var user = authService.resolveOrCreateUser(context.getSession(), realm, phoneNumber);

        if (user != null && !user.isEnabled()) {
            event.detail("phone_number", phoneNumber)
                    .user(user)
                    .error(Errors.USER_DISABLED);
            context.clearUser();
            context.resetFlow();
            return;
        }

        context.setUser(user);
        context
                .getAuthenticationSession()
                .setAuthNote(PhoneKey.ATTEMPTED_PHONE_NUMBER, phoneNumber);

        context.success();
    }

    @Override
    public String getDisplayType() {
        return "SMS -3 Choose user by Phone number";
    }

    @Override
    public String getReferenceCategory() {
        return "phone-choose";
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public String getHelpText() {
        return "Choose a user, by his/her phone number, to reset credentials for";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

}
