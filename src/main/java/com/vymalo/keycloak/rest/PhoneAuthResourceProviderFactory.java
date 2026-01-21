package com.vymalo.keycloak.rest;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/**
 * Factory for creating PhoneAuthResourceProvider instances.
 * This is registered as a Keycloak SPI provider.
 */
public class PhoneAuthResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String ID = "phone-auth";

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new PhoneAuthResourceProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        // Initialize any configuration if needed
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Post-initialization if needed
    }

    @Override
    public void close() {
        // Cleanup if needed
    }

    @Override
    public String getId() {
        return ID;
    }
}
