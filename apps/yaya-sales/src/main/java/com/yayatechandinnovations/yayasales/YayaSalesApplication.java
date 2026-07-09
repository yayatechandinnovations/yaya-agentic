package com.yayatechandinnovations.yayasales;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * yaya-sales — standalone multi-tenant sales system (apps/, design
 * {@code docs/design/yaya-sales-design.md}). Modular monolith: each direct
 * sub-package is a Spring Modulith application module (§6); {@code platform}
 * is the shared correctness spine (S0), domain modules land per §12.4.
 */
@SpringBootApplication
@EnableScheduling
public class YayaSalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(YayaSalesApplication.class, args);
    }
}
