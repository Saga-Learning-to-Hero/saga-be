package com.saga.be.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
	void unauthenticatedCannotWriteAdminSubjects() throws Exception {
		mockMvc.perform(post("/api/admin/subjects")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"SWP391\",\"nameEnglish\":\"Software Development Project\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void studentCannotWriteAdminSubjects() throws Exception {
		mockMvc.perform(post("/api/admin/subjects")
						.with(csrf())
						.with(authentication(auth(AccountRole.STUDENT, "hash")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"SWP391\",\"nameEnglish\":\"Software Development Project\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void passwordSetupRequiredBlocksBusinessApi() throws Exception {
		mockMvc.perform(get("/api/student/anything").with(authentication(auth(AccountRole.STUDENT, null, "google-sub"))))
				.andExpect(status().isForbidden())
				.andExpect(content().json("{\"code\":\"PASSWORD_SETUP_REQUIRED\",\"message\":\"Password setup is required.\"}"));
	}

	@Test
	void studentCannotWriteAdminSemesters() throws Exception {
		mockMvc.perform(post("/api/admin/semesters")
						.with(csrf())
						.with(authentication(auth(AccountRole.STUDENT, "hash")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"FA26\",\"name\":\"Fall 2026\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-12-31\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void unauthenticatedCannotWriteAdminCourses() throws Exception {
		mockMvc.perform(post("/api/admin/courses")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"academicClassId\":\"00000000-0000-0000-0000-000000000001\",\"subjectId\":\"00000000-0000-0000-0000-000000000002\",\"syllabusVersionId\":\"00000000-0000-0000-0000-000000000003\",\"lecturerId\":\"00000000-0000-0000-0000-000000000004\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void studentCannotWriteAdminCourses() throws Exception {
		mockMvc.perform(post("/api/admin/courses")
						.with(csrf())
						.with(authentication(auth(AccountRole.STUDENT, "hash")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"academicClassId\":\"00000000-0000-0000-0000-000000000001\",\"subjectId\":\"00000000-0000-0000-0000-000000000002\",\"syllabusVersionId\":\"00000000-0000-0000-0000-000000000003\",\"lecturerId\":\"00000000-0000-0000-0000-000000000004\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void studentCannotExportOrImportCourseRoster() throws Exception {
		UUID courseId = UUID.fromString("00000000-0000-0000-0000-000000000099");
		mockMvc.perform(get("/api/admin/courses/" + courseId + "/roster/template")
						.with(authentication(auth(AccountRole.STUDENT, "hash"))))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/courses/" + courseId + "/roster")
						.with(authentication(auth(AccountRole.STUDENT, "hash"))))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/admin/courses/" + courseId + "/roster/import/preview")
						.with(csrf())
						.with(authentication(auth(AccountRole.STUDENT, "hash")))
						.contentType(MediaType.MULTIPART_FORM_DATA))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/admin/courses/" + courseId + "/roster/import/confirm")
						.with(csrf())
						.with(authentication(auth(AccountRole.STUDENT, "hash")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"previewToken\":\"abc\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void unauthenticatedCannotExportOrImportCourseRoster() throws Exception {
		UUID courseId = UUID.fromString("00000000-0000-0000-0000-000000000099");
		mockMvc.perform(get("/api/admin/courses/" + courseId + "/roster/template"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/admin/courses/" + courseId + "/roster/import/preview").with(csrf()))
				.andExpect(status().isForbidden());
	}

	@Test
	void studentCannotUseDevEmailSmokeEndpoint() throws Exception {
		mockMvc.perform(post("/api/admin/dev/email-test")
						.with(csrf())
						.with(authentication(auth(AccountRole.STUDENT, "hash")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"to\":\"test@example.com\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void unauthenticatedCannotUseDevEmailSmokeEndpoint() throws Exception {
		mockMvc.perform(post("/api/admin/dev/email-test")
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"to\":\"test@example.com\"}"))
				.andExpect(status().isForbidden());
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

	@Test
	void lecturerCannotAccessStudentApi() throws Exception {
		mockMvc.perform(get("/api/student/anything").with(authentication(auth(AccountRole.LECTURER, "hash"))))
				.andExpect(status().isForbidden());
	}

	@Test
	void studentCoursesEndpointStaysStudentOnly() throws Exception {
		mockMvc.perform(get("/api/student/courses")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/student/courses").with(authentication(auth(AccountRole.LECTURER, "hash"))))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/student/courses").with(authentication(auth(AccountRole.ADMIN, "hash"))))
				.andExpect(status().isForbidden());
	}

	@Test
	void lecturerWithPasswordCanAccessLecturerApi() throws Exception {
		mockMvc.perform(get("/api/lecturer/anything").with(authentication(auth(AccountRole.LECTURER, "hash"))))
				.andExpect(status().isOk())
				.andExpect(content().string("lecturer-ok"));
	}

	@Test
	void adminCanAccessLecturerApi() throws Exception {
		mockMvc.perform(get("/api/lecturer/anything").with(authentication(auth(AccountRole.ADMIN, "hash"))))
				.andExpect(status().isOk())
				.andExpect(content().string("lecturer-ok"));
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
