package com.example.kairo.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@MapperScan("com.example.kairo.platform.persistence.mapper")
public class KairoPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(KairoPlatformApplication.class, args);
    }
}
