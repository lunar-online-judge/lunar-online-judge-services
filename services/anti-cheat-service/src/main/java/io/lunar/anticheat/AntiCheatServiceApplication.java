package io.lunar.anticheat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AntiCheatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AntiCheatServiceApplication.class, args);
    }
}
