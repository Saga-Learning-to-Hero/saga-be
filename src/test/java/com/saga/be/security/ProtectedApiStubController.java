package com.saga.be.security;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("test")
class ProtectedApiStubController {

	@GetMapping("/api/student/anything")
	String student() {
		return "student-ok";
	}

	@GetMapping("/api/lecturer/anything")
	String lecturer() {
		return "lecturer-ok";
	}

	@GetMapping("/api/admin/anything")
	String admin() {
		return "admin-ok";
	}

	@GetMapping(value = "/api/projects/{projectId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	String projectEvents(@PathVariable String projectId) {
		return "sse-ok";
	}

	@PostMapping("/api/webhooks/github")
	ResponseEntity<Void> githubWebhook() {
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/webhooks/jira")
	ResponseEntity<Void> jiraWebhook() {
		return ResponseEntity.noContent().build();
	}
}
