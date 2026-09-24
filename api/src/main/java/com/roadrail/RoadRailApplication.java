package com.roadrail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RoadRailApplication {
    public static void main(String[] args) {
        SpringApplication.run(RoadRailApplication.class, args);
    }
}
