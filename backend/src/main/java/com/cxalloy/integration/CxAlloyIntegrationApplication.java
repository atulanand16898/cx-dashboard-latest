package com.cxalloy.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CxAlloyIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(CxAlloyIntegrationApplication.class, args);
    }
}
