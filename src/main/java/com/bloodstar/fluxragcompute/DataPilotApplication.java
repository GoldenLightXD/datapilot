package com.bloodstar.fluxragcompute;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.bloodstar.fluxragcompute.mapper")
@SpringBootApplication
public class DataPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataPilotApplication.class, args);
    }
}
