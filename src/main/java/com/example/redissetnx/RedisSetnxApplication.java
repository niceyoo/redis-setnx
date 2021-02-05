package com.example.redissetnx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RedisSetnxApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisSetnxApplication.class, args);
    }

}
