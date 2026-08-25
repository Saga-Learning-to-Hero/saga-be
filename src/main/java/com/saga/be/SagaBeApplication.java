package com.saga.be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SagaBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(SagaBeApplication.class, args);
	}

}
