package com.saga.be.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public class DeveloperLandingController {

	@GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<Resource> landing() {
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_HTML)
				.body(new ClassPathResource("static/index.html"));
	}
}
