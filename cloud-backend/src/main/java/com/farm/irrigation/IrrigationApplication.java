package com.farm.irrigation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class IrrigationApplication {

    public static void main(String[] args) {
        SpringApplication.run(IrrigationApplication.class, args);
    }
}
