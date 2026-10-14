package com.kama.jchatmind.demo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.demo")
public class DemoProperties {
    private String agentId = "";
    private List<String> publicKbIds = List.of();
    private List<String> showcaseSessionIds = List.of();
}
