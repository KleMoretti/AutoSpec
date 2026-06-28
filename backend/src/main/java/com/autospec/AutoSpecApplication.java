package com.autospec;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.autospec.mapper")
@SpringBootApplication
public class AutoSpecApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoSpecApplication.class, args);
    }
}
