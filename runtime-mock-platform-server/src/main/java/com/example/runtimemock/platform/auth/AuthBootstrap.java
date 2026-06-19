package com.example.runtimemock.platform.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
final class AuthBootstrap implements ApplicationRunner {

    private final AuthProperties properties;
    private final AccessTokenService accessTokenService;

    AuthBootstrap(AuthProperties properties, AccessTokenService accessTokenService) {
        this.properties = properties;
        this.accessTokenService = accessTokenService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("local-token".equalsIgnoreCase(properties.getMode())) {
            accessTokenService.installBootstrapToken(
                    properties.getBootstrapToken(),
                    properties.getBootstrapActor(),
                    properties.getBootstrapTtlDays()
            );
        }
    }
}
