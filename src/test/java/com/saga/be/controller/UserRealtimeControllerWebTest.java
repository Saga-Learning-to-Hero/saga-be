package com.saga.be.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.security.SagaAuthentications;
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
class UserRealtimeControllerWebTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void anonymousCannotSubscribe() throws Exception {
		mockMvc.perform(get("/api/users/me/events")).andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedUserConnectsToOwnStreamAndCannotSelectAnotherUser() throws Exception {
		mockMvc.perform(get("/api/users/me/events").with(authentication(auth(AccountRole.STUDENT))))
				.andExpect(status().isOk())
				.andExpect(content().string("user-sse-ok"));
		mockMvc.perform(get("/api/users/" + UUID.randomUUID() + "/events")
						.with(authentication(auth(AccountRole.STUDENT))))
				.andExpect(status().isNotFound());
	}

	private static org.springframework.security.core.Authentication auth(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("a@fpt.edu.vn");
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("hash");
		return SagaAuthentications.authenticated(account);
	}
}
