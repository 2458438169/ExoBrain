package com.kama.jchatmind.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    private String publicOrigin = "";
    private boolean secureCookie = true;
    private long sessionHours = 12;
    private String bootstrapAdminUsername = "admin";
    private String bootstrapAdminPasswordFile = "";
    public String cookieName() { return secureCookie ? "__Host-exobrain_session" : "exobrain_session"; }
}
