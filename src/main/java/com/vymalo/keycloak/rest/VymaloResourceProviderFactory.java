package com.vymalo.keycloak.rest;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/**
 * Factory for creating VymaloResourceProvider instances.
 * This is registered as a Keycloak SPI provider.
 */
public class VymaloResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String ID = "vymalo";

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new VymaloResourceProvider(session);
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
