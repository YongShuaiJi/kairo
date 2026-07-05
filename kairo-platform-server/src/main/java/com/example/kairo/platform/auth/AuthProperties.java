package com.example.kairo.platform.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kairo.platform.auth")
public class AuthProperties {

    private String mode = "local-token";
    private String bootstrapToken = "";
    private String bootstrapActor = "system";
    private long bootstrapTtlDays = 365;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getBootstrapToken() {
        return bootstrapToken;
    }

    public void setBootstrapToken(String bootstrapToken) {
        this.bootstrapToken = bootstrapToken;
    }

    public String getBootstrapActor() {
        return bootstrapActor;
    }

    public void setBootstrapActor(String bootstrapActor) {
        this.bootstrapActor = bootstrapActor;
    }

    public long getBootstrapTtlDays() {
        return bootstrapTtlDays;
    }

    public void setBootstrapTtlDays(long bootstrapTtlDays) {
        this.bootstrapTtlDays = bootstrapTtlDays;
    }
}
