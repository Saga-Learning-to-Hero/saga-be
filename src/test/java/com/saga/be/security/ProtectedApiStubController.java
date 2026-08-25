package com.saga.be.security;

import com.saga.be.entity.enums.AccountRole;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
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
}
