package com.kama.jchatmind.models;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.models")
public class ModelSettings {
    private String encryptionKeyFile = "";
    private int requestTimeoutSeconds = 90;

    public String getEncryptionKeyFile() { return encryptionKeyFile; }
    public void setEncryptionKeyFile(String encryptionKeyFile) { this.encryptionKeyFile = encryptionKeyFile; }
    public int getRequestTimeoutSeconds() { return Math.min(120, Math.max(5, requestTimeoutSeconds)); }
    public void setRequestTimeoutSeconds(int seconds) { this.requestTimeoutSeconds = seconds; }
}
