package com.saga.be.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LegalPagesController {

	@GetMapping(value = "/privacy", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<Resource> privacy() {
		return html("static/legal/privacy.html");
	}

	@GetMapping(value = "/terms", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<Resource> terms() {
		return html("static/legal/terms.html");
	}

	private static ResponseEntity<Resource> html(String classpathLocation) {
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_HTML)
				.body(new ClassPathResource(classpathLocation));
	}
}
