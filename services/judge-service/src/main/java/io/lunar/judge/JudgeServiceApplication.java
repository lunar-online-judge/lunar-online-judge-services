package io.lunar.judge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * @ConfigurationPropertiesScan：让 config 包下的 @ConfigurationProperties 生效，
 * 把"运行时可变"的消费参数（如去重登记 TTL）交给 Nacos 托管（docs/middleware.md"配置中心"节）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JudgeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(JudgeServiceApplication.class, args);
    }
}
