package com.saga.be.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.admin.AdminAuditLogPageResponse;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AdminAuditLogQueryRow;
import com.saga.be.repository.AuditLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogQueryServiceTest {

	@Mock
	private AuditLogRepository auditLogs;

	private AdminAuditLogQueryService service;

	@BeforeEach
	void setUp() {
		service = new AdminAuditLogQueryService(auditLogs, new ObjectMapper());
	}

	@Test
	void listParsesStoredJsonAndDoesNotInventSecrets() throws Exception {
		UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		UUID actor = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		when(auditLogs.searchAdminAuditLogs(
						isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(
						List.of(row(
								id,
								actor,
								"{\"password\":\"[REDACTED]\",\"fullName\":\"Ada\"}",
								null,
								"{\"ok\":true}")),
						PageRequest.of(0, 50),
						1));
		AdminAuditLogPageResponse page = service.list(null, null, null, null, null, null, null, null);
		assertEquals(1, page.total());
		assertEquals("COURSE_CREATED", page.items().getFirst().action());
		assertEquals("[REDACTED]", ((java.util.Map<?, ?>) page.items().getFirst().before()).get("password"));
		assertEquals("Ada", ((java.util.Map<?, ?>) page.items().getFirst().before()).get("fullName"));
		assertNull(page.items().getFirst().after());
		assertEquals(Boolean.TRUE, ((java.util.Map<?, ?>) page.items().getFirst().metadata()).get("ok"));
		assertNull(page.items().getFirst().contextProjectId());
		assertNull(page.items().getFirst().contextProjectNameSnapshot());
		assertNull(page.items().getFirst().contextTeamId());
		assertNull(page.items().getFirst().contextTeamNoSnapshot());
		assertNull(page.items().getFirst().contextTeamNameSnapshot());
	}

	@Test
	void listMapsStoredSnapshotsWithoutInventingCurrentNames() {
		UUID id = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		UUID actor = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		UUID projectId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
		UUID teamId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
		when(auditLogs.searchAdminAuditLogs(
						isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(
						List.of(new AdminAuditLogQueryRow(
								id,
								actor,
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
								"{\"note\":\"ok\"}",
								AuditSource.API,
								"req-1",
								"127.0.0.1",
								"test",
								LocalDateTime.of(2026, 3, 4, 5, 6))),
						PageRequest.of(0, 50),
						1));
		AdminAuditLogPageResponse page = service.list(null, null, null, null, null, null, null, null);
		assertEquals(projectId, page.items().getFirst().contextProjectId());
		assertEquals("SAGA V1", page.items().getFirst().contextProjectNameSnapshot());
		assertEquals(teamId, page.items().getFirst().contextTeamId());
		assertEquals(2, page.items().getFirst().contextTeamNoSnapshot());
		assertEquals("Alpha", page.items().getFirst().contextTeamNameSnapshot());
		assertEquals("ok", ((java.util.Map<?, ?>) page.items().getFirst().metadata()).get("note"));
	}

	@Test
	void listRejectsInvertedTimeRangeAndInvalidSize() {
		LocalDateTime from = LocalDateTime.of(2026, 2, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 1, 1, 0, 0);
		AcademicException range = assertThrows(
				AcademicException.class, () -> service.list(null, null, null, null, from, to, 0, 50));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, range.getCode());
		AcademicException size = assertThrows(
				AcademicException.class, () -> service.list(null, null, null, null, null, null, 0, 0));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, size.getCode());
	}

	@Test
	void listPassesFilters() {
		UUID actor = UUID.randomUUID();
		UUID entity = UUID.randomUUID();
		LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 12, 31, 23, 59);
		when(auditLogs.searchAdminAuditLogs(
						eq(actor),
						eq("PROJECT_CREATED"),
						eq("project"),
						eq(entity),
						eq(from),
						eq(to),
						eq(PageRequest.of(2, 10))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));
		service.list(actor, " PROJECT_CREATED ", " project ", entity, from, to, 2, 10);
		verify(auditLogs)
				.searchAdminAuditLogs(actor, "PROJECT_CREATED", "project", entity, from, to, PageRequest.of(2, 10));
	}

	private static AdminAuditLogQueryRow row(UUID id, UUID actor, String before, String after, String metadata) {
		return new AdminAuditLogQueryRow(
				id,
				actor,
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
				before,
				after,
				metadata,
				AuditSource.API,
				"req-1",
				"127.0.0.1",
				"test",
				LocalDateTime.of(2026, 3, 4, 5, 6));
	}
}
