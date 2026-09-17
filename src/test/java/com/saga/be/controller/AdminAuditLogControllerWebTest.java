package com.saga.be.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.admin.AdminAuditLogPageResponse;
import com.saga.be.dto.admin.AdminAuditLogResponse;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.service.admin.AdminAuditLogQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogControllerWebTest {

	@Mock
	private AdminAuditLogQueryService auditLogs;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new AdminAuditLogController(auditLogs))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void listReturnsRedactedJsonAsStored() throws Exception {
		UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		when(auditLogs.list(null, null, null, null, null, null, null, null))
				.thenReturn(new AdminAuditLogPageResponse(
						List.of(new AdminAuditLogResponse(
								id,
								LocalDateTime.of(2026, 3, 4, 5, 6),
								UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
								"Ada",
								"ADMIN",
								"ada@saga.local",
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								null,
								"COURSE_CREATED",
								"course",
								UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
								new ObjectMapper().readValue("{\"password\":\"[REDACTED]\"}", Object.class),
								null,
								null,
								"API",
								"req-1",
								"127.0.0.1",
								"test")),
						0,
						50,
						1));
		mockMvc.perform(get("/api/admin/audit-logs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].action").value("COURSE_CREATED"))
				.andExpect(jsonPath("$.items[0].before.password").value("[REDACTED]"))
				.andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
	}

	@Test
	void listSerializesProjectTeamSnapshotsAndKeepsContextIds() throws Exception {
		UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		UUID projectId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
		UUID teamId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
		when(auditLogs.list(null, null, null, null, null, null, null, null))
				.thenReturn(new AdminAuditLogPageResponse(
						List.of(new AdminAuditLogResponse(
								id,
								LocalDateTime.of(2026, 3, 4, 5, 6),
								UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
								"Ada",
								"ADMIN",
								"ada@saga.local",
								null,
								null,
								null,
								null,
								null,
								teamId,
								2,
								"Alpha",
								projectId,
								"SAGA V1",
								"PROJECT_CREATED",
								"project",
								projectId,
								null,
								null,
								null,
								"API",
								"req-1",
								"127.0.0.1",
								"test")),
						0,
						50,
						1));
		mockMvc.perform(get("/api/admin/audit-logs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].contextProjectId").value(projectId.toString()))
				.andExpect(jsonPath("$.items[0].contextProjectNameSnapshot").value("SAGA V1"))
				.andExpect(jsonPath("$.items[0].contextTeamId").value(teamId.toString()))
				.andExpect(jsonPath("$.items[0].contextTeamNoSnapshot").value(2))
				.andExpect(jsonPath("$.items[0].contextTeamNameSnapshot").value("Alpha"))
				.andExpect(jsonPath("$.items[0].project").doesNotExist())
				.andExpect(jsonPath("$.items[0].team").doesNotExist());
	}

	@Test
	void mutationMethodsAreNotMapped() throws Exception {
		mockMvc.perform(post("/api/admin/audit-logs").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isMethodNotAllowed());
		mockMvc.perform(patch("/api/admin/audit-logs").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isMethodNotAllowed());
		mockMvc.perform(delete("/api/admin/audit-logs")).andExpect(status().isMethodNotAllowed());
	}
}
