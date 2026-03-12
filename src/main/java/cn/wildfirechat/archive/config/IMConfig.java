package cn.wildfirechat.archive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "im")
public class IMConfig {
    private String adminUrl = "http://localhost:18080";
    private String secret = "123456";
}
