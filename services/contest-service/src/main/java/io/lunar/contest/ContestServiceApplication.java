package io.lunar.contest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ContestServiceApplication {
    public static void main(String[] args) { SpringApplication.run(ContestServiceApplication.class, args); }
}
