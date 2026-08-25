package com.saga.be.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAuthorizationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void unauthenticatedProtectedEndpointIsDenied() throws Exception {
		mockMvc.perform(get("/api/student/anything")).andExpect(status().isUnauthorized());
	}

	@Test
	void anonymousMeIsPermitted() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"authenticated\":false,\"passwordSetupRequired\":false,\"user\":null}"));
	}

	@Test
	void studentCannotAccessLecturerEndpoint() throws Exception {
		mockMvc.perform(get("/api/lecturer/anything").with(authentication(auth(AccountRole.STUDENT, "hash"))))
				.andExpect(status().isForbidden());
	}

	@Test
	void studentCannotAccessAdminEndpoint() throws Exception {
		mockMvc.perform(get("/api/admin/anything").with(authentication(auth(AccountRole.STUDENT, "hash"))))
				.andExpect(status().isForbidden());
	}

	@Test
	void passwordSetupRequiredBlocksBusinessApi() throws Exception {
		mockMvc.perform(get("/api/student/anything").with(authentication(auth(AccountRole.STUDENT, null, "google-sub"))))
				.andExpect(status().isForbidden())
				.andExpect(content().json("{\"code\":\"PASSWORD_SETUP_REQUIRED\",\"message\":\"Password setup is required.\"}"));
	}

	@Test
	void lecturerPasswordSetupRequiredBlocksBusinessApi() throws Exception {
		mockMvc.perform(get("/api/lecturer/anything").with(authentication(auth(AccountRole.LECTURER, null, "google-sub"))))
				.andExpect(status().isForbidden())
				.andExpect(content().json("{\"code\":\"PASSWORD_SETUP_REQUIRED\",\"message\":\"Password setup is required.\"}"));
	}

	@Test
	void restrictedStudentAndLecturerMayStillCallMe() throws Exception {
		mockMvc.perform(get("/api/auth/me").with(authentication(auth(AccountRole.STUDENT, null, "google-sub"))))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"authenticated\":true,\"passwordSetupRequired\":true}"));
		mockMvc.perform(get("/api/auth/me").with(authentication(auth(AccountRole.LECTURER, null, "google-sub"))))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"authenticated\":true,\"passwordSetupRequired\":true}"));
	}

	@Test
	void studentWithPasswordCanAccessStudentApi() throws Exception {
		mockMvc.perform(get("/api/student/anything").with(authentication(auth(AccountRole.STUDENT, "hash", null))))
				.andExpect(status().isOk())
				.andExpect(content().string("student-ok"));
	}

	private static org.springframework.security.core.Authentication auth(AccountRole role, String passwordHash) {
		return auth(role, passwordHash, null);
	}

	private static org.springframework.security.core.Authentication auth(
			AccountRole role, String passwordHash, String googleSubject) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("a@fpt.edu.vn");
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash(passwordHash);
		account.setGoogleSubject(googleSubject);
		return SagaAuthentications.authenticated(account);
	}
}
