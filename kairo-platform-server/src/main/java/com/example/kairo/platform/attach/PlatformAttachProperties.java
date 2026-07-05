package com.example.kairo.platform.attach;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kairo.platform.attach")
public class PlatformAttachProperties {

    private String agentJar = "/app/kairo-agent-bootstrap.jar";
    private String coreJar = "/app/kairo-agent-core-modern.jar";
    private String bootstrapJar = "/app/kairo-bootstrap-api.jar";
    private String agentHost = "127.0.0.1";
    private int agentPort = 0;
    private String platformUrl = "http://127.0.0.1:18280";
    private String platformToken = "";
    private String sidecarToken = "";
    private long commandMaxAttempts = 3;

    public String getAgentJar() {
        return agentJar;
    }

    public void setAgentJar(String agentJar) {
        this.agentJar = agentJar;
    }

    public String getCoreJar() {
        return coreJar;
    }

    public void setCoreJar(String coreJar) {
        this.coreJar = coreJar;
    }

    public String getBootstrapJar() {
        return bootstrapJar;
    }

    public void setBootstrapJar(String bootstrapJar) {
        this.bootstrapJar = bootstrapJar;
    }

    public String getAgentHost() {
        return agentHost;
    }

    public void setAgentHost(String agentHost) {
        this.agentHost = agentHost;
    }

    public int getAgentPort() {
        return agentPort;
    }

    public void setAgentPort(int agentPort) {
        this.agentPort = agentPort;
    }

    public String getPlatformUrl() {
        return platformUrl;
    }

    public void setPlatformUrl(String platformUrl) {
        this.platformUrl = platformUrl;
    }

    public String getPlatformToken() {
        return platformToken;
    }

    public void setPlatformToken(String platformToken) {
        this.platformToken = platformToken;
    }

    public String getSidecarToken() {
        return sidecarToken;
    }

    public void setSidecarToken(String sidecarToken) {
        this.sidecarToken = sidecarToken;
    }

    public long getCommandMaxAttempts() {
        return commandMaxAttempts;
    }

    public void setCommandMaxAttempts(long commandMaxAttempts) {
        this.commandMaxAttempts = commandMaxAttempts;
    }
}
