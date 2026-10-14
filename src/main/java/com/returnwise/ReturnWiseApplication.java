package com.returnwise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ReturnWiseApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReturnWiseApplication.class, args);
    }
}
