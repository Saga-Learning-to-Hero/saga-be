package com.saga.be.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.audit.AuditLog;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.repository.AuditLogRepository;
import com.saga.be.repository.StudentProfileRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuditServiceTest {

	@Test
	void actorSnapshotsArePersistedAndProfileActionsHaveNoClassContext() {
		AuditLogRepository logs = Mockito.mock(AuditLogRepository.class);
		StudentProfileRepository students = Mockito.mock(StudentProfileRepository.class);
		Mockito.when(logs.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
		UserAccount actor = new UserAccount();
		actor.setId(UUID.randomUUID());
		actor.setFullName("Alice Nguyen");
		actor.setEmail("alice@example.com");
		actor.setAccountRole(AccountRole.STUDENT);
		StudentProfile profile = new StudentProfile();
		profile.setStudentCode("SE123456");
		Mockito.when(students.findByUserAccount_Id(actor.getId())).thenReturn(Optional.of(profile));
		AuditService service = new AuditService(logs, students, new AuditRedactor(new ObjectMapper()), new ObjectMapper());
		AuditLog saved = service.record(
				actor,
				null,
				null,
				"GITHUB_IDENTITY_LINKED",
				"identity_map",
				UUID.randomUUID(),
				Map.of("password", "nope"),
				Map.of("login", "alice-gh"),
				Map.of("refresh_token", "rt"),
				AuditSource.OAUTH,
				"req-1",
				"127.0.0.1",
				"JUnit");
		assertEquals("Alice Nguyen", saved.getActorFullNameSnapshot());
		assertEquals("SE123456", saved.getActorStudentCodeSnapshot());
		assertEquals("STUDENT", saved.getActorRoleSnapshot());
		assertNull(saved.getContextClassId());
		assertTrue(saved.getBeforeData().contains(AuditRedactor.REDACTED));
		assertTrue(saved.getMetadataJson().contains(AuditRedactor.REDACTED));
		ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
		Mockito.verify(logs).save(captor.capture());
		assertEquals("GITHUB_IDENTITY_LINKED", captor.getValue().getAction());
	}
}
