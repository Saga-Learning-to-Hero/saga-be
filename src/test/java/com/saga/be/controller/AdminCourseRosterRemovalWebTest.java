package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.roster.CourseRosterService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The ADMIN roster DELETE while the FE moves from "no body" to {"reason"}. */
@ExtendWith(MockitoExtension.class)
class AdminCourseRosterRemovalWebTest {

	private static final UUID COURSE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID ENROLLMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final String URL = "/api/admin/courses/" + COURSE + "/roster/enrollments/" + ENROLLMENT;

	@Mock
	private CourseRosterService roster;

	@Mock
	private UserAccountRepository users;

	private UserAccount admin;

	@BeforeEach
	void setUp() {
		admin = new UserAccount();
		admin.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
		admin.setEmail("root@saga.local");
		admin.setAccountRole(AccountRole.ADMIN);
		admin.setAccountStatus(AccountStatus.ACTIVE);
		admin.setPasswordHash("hash");
		lenient().when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
		SecurityContextHolder.getContext().setAuthentication(SagaAuthentications.authenticated(admin));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private MockMvc mvc(boolean reasonRequired) {
		return MockMvcBuilders.standaloneSetup(new AdminCourseRosterController(roster, users, reasonRequired))
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void legacyNoBodyDeleteStillWorksWhileTheReasonIsNotYetRequired() throws Exception {
		mvc(false).perform(delete(URL)).andExpect(status().isOk());

		verify(roster).removeEnrollmentWithoutReason(eq(COURSE), eq(ENROLLMENT), eq(admin), any(AuditRequest.class));
	}

	@Test
	void noBodyDeleteIsRejectedOnceTheReasonIsRequired() throws Exception {
		mvc(true).perform(delete(URL))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));

		verifyNoInteractions(roster);
	}

	@Test
	void reasonInTheBodyIsPassedOnInBothModes() throws Exception {
		for (boolean required : new boolean[] {false, true}) {
			mvc(required).perform(delete(URL)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"reason\":\"Transferred to SE1803\"}"))
					.andExpect(status().isOk());
		}

		verify(roster, org.mockito.Mockito.times(2))
				.removeEnrollment(eq(COURSE), eq(ENROLLMENT), eq(admin), eq("Transferred to SE1803"), any(AuditRequest.class));
	}

	@Test
	void blankReasonInTheBodyIsRejectedEvenDuringTheTransition() throws Exception {
		mvc(false).perform(delete(URL).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));

		verifyNoInteractions(roster);
	}
}
