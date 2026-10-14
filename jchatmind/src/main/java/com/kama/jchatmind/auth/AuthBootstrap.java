package com.kama.jchatmind.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;

/** Tables are installed by a DB owner migration, never by the runtime database role. */
@Component
public class AuthBootstrap implements ApplicationRunner {
    private final AuthService auth;
    private final AuthRepository repository;
    private final AuthProperties properties;
    public AuthBootstrap(AuthService auth, AuthRepository repository, AuthProperties properties) {
        this.auth = auth; this.repository = repository; this.properties = properties;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        String file = properties.getBootstrapAdminPasswordFile();
        if (file == null || file.isBlank()) return;
        if (repository.countUsers() > 0) return;
        String password = Files.readString(Path.of(file)).stripTrailing();
        auth.bootstrap(properties.getBootstrapAdminUsername(), password);
    }
}
