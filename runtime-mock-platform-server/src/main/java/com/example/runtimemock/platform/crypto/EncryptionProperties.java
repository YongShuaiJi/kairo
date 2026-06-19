package com.example.runtimemock.platform.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("runtime-mock.platform.encryption")
public class EncryptionProperties {

    private String masterKeyBase64 = "";
    private String masterKeyFile = "";
    private String keyVersion = "local-v1";

    public String getMasterKeyBase64() {
        return masterKeyBase64;
    }

    public void setMasterKeyBase64(String masterKeyBase64) {
        this.masterKeyBase64 = masterKeyBase64;
    }

    public String getMasterKeyFile() {
        return masterKeyFile;
    }

    public void setMasterKeyFile(String masterKeyFile) {
        this.masterKeyFile = masterKeyFile;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(String keyVersion) {
        this.keyVersion = keyVersion;
    }
}
