package com.vymalo.keycloak.service;

import com.vymalo.keycloak.constants.PhoneNumberHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PhoneAuthenticationServiceTest {

    private static final String PHONE = "+15555550123";

    @Mock
    private KeycloakSession session;

    @Mock
    private RealmModel realm;

    @Mock
    private UserProvider userProvider;

    @BeforeEach
    void setUp() {
        when(session.users()).thenReturn(userProvider);
    }

    @Test
    void createsUserWhenPhoneIsNew() {
        PhoneAuthenticationService service = new PhoneAuthenticationService(session, realm);
        UserModel newUser = mock(UserModel.class);
        String attrName = PhoneNumberHelper.DEFAULT_PHONE_KEY_NAME;

        when(userProvider.searchForUserByUserAttributeStream(realm, attrName, PHONE))
            .thenReturn(Stream.empty());
        when(userProvider.getUserByUsername(realm, PHONE)).thenReturn(null);
        when(userProvider.addUser(realm, PHONE)).thenReturn(newUser);

        UserModel result = service.resolveOrCreateUser(session, realm, PHONE);

        assertSame(newUser, result);
        verify(userProvider).addUser(realm, PHONE);
        verify(newUser).setAttribute(attrName, Collections.singletonList(PHONE));
        verify(newUser).setEnabled(true);
    }

    @Test
    void findsExistingUserByUsername() {
        PhoneAuthenticationService service = new PhoneAuthenticationService(session, realm);
        UserModel existingUser = mock(UserModel.class);
        String attrName = PhoneNumberHelper.DEFAULT_PHONE_KEY_NAME;

        when(userProvider.searchForUserByUserAttributeStream(realm, attrName, PHONE))
            .thenReturn(Stream.empty());
        when(userProvider.getUserByUsername(realm, PHONE)).thenReturn(existingUser);
        when(existingUser.getFirstAttribute(attrName)).thenReturn(null);

        UserModel result = service.resolveOrCreateUser(session, realm, PHONE);

        assertSame(existingUser, result);
        verify(userProvider, never()).addUser(realm, PHONE);
        verify(existingUser).setAttribute(attrName, Collections.singletonList(PHONE));
    }

    @Test
    void backfillsPhoneNumberAttribute() {
        PhoneAuthenticationService service = new PhoneAuthenticationService(session, realm);
        UserModel existingUser = mock(UserModel.class);
        String attrName = PhoneNumberHelper.DEFAULT_PHONE_KEY_NAME;

        when(userProvider.searchForUserByUserAttributeStream(realm, attrName, PHONE))
            .thenReturn(Stream.empty());
        when(userProvider.getUserByUsername(realm, PHONE)).thenReturn(existingUser);
        when(existingUser.getFirstAttribute(attrName)).thenReturn("");

        service.resolveOrCreateUser(session, realm, PHONE);

        verify(existingUser).setAttribute(attrName, Collections.singletonList(PHONE));
    }

    @Test
    void doesNotCreateDuplicatesOnRepeatedCalls() {
        PhoneAuthenticationService service = new PhoneAuthenticationService(session, realm);
        String attrName = PhoneNumberHelper.DEFAULT_PHONE_KEY_NAME;
        AtomicReference<UserModel> userRef = new AtomicReference<>();

        when(userProvider.searchForUserByUserAttributeStream(realm, attrName, PHONE))
            .thenAnswer(invocation -> {
                UserModel existing = userRef.get();
                return existing == null ? Stream.empty() : Stream.of(existing);
            });
        when(userProvider.getUserByUsername(realm, PHONE))
            .thenAnswer(invocation -> userRef.get());
        when(userProvider.addUser(realm, PHONE))
            .thenAnswer(invocation -> {
                UserModel created = mock(UserModel.class);
                userRef.set(created);
                return created;
            });

        UserModel first = service.resolveOrCreateUser(session, realm, PHONE);
        UserModel second = service.resolveOrCreateUser(session, realm, PHONE);

        assertSame(first, second);
        verify(userProvider, times(1)).addUser(realm, PHONE);
    }
}
