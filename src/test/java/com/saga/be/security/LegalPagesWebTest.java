package com.saga.be.security;

import static org.hamcrest.Matchers.containsString;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegalPagesWebTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void privacyIsPublicAnonymously() throws Exception {
		mockMvc.perform(get("/privacy"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(containsString("SAGA Learning Privacy Policy")));
	}

	@Test
	void termsIsPublicAnonymously() throws Exception {
		mockMvc.perform(get("/terms"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(containsString("SAGA Learning Terms of Service")));
	}

	@Test
	void legalStaticPathsArePublic() throws Exception {
		mockMvc.perform(get("/legal/privacy.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("SAGA Learning Privacy Policy")));
		mockMvc.perform(get("/legal/terms.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("SAGA Learning Terms of Service")));
	}

	@Test
	void authenticatedUserCanReadLegalPages() throws Exception {
		mockMvc.perform(get("/privacy").with(authentication(auth(AccountRole.STUDENT))))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("SAGA Learning Privacy Policy")));
		mockMvc.perform(get("/terms").with(authentication(auth(AccountRole.ADMIN))))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("SAGA Learning Terms of Service")));
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
