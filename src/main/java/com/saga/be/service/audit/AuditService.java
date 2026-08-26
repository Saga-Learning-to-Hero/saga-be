package com.saga.be.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.audit.AuditLog;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.repository.AuditLogRepository;
import com.saga.be.repository.StudentProfileRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AuditService {

	private final AuditLogRepository auditLogs;
	private final StudentProfileRepository students;
	private final AuditRedactor redactor;
	private final ObjectMapper mapper;

	public AuditService(
			AuditLogRepository auditLogs,
			StudentProfileRepository students,
			AuditRedactor redactor,
			ObjectMapper mapper) {
		this.auditLogs = auditLogs;
		this.students = students;
		this.redactor = redactor;
		this.mapper = mapper;
	}

	@Transactional
	public AuditLog record(
			UserAccount actor,
			Project project,
			Team team,
			String action,
			String entityType,
			UUID entityId,
			Map<String, Object> before,
			Map<String, Object> after,
			Map<String, Object> metadata,
			AuditSource source,
			String requestId,
			String ip,
			String userAgent) {
		AuditLog log = new AuditLog();
		if (actor != null) {
			log.setActorUser(actor);
			log.setActorFullNameSnapshot(actor.getFullName());
			log.setActorRoleSnapshot(actor.getAccountRole() == null ? null : actor.getAccountRole().name());
			log.setActorEmailSnapshot(actor.getEmail());
			students.findByUserAccount_Id(actor.getId()).map(StudentProfile::getStudentCode).ifPresent(log::setActorStudentCodeSnapshot);
		}
		if (project != null && project.getCourse() != null) {
			log.setContextCourseId(project.getCourse().getId());
			if (project.getCourse().getAcademicClass() != null) {
				log.setContextClassId(project.getCourse().getAcademicClass().getId());
				log.setContextClassCodeSnapshot(project.getCourse().getAcademicClass().getClassCode());
				log.setContextClassNameSnapshot(project.getCourse().getAcademicClass().getName());
			}
			log.setContextProjectId(project.getId());
		}
		if (team != null) {
			log.setContextTeamId(team.getId());
		}
		log.setAction(action);
		log.setEntityType(entityType);
		log.setEntityId(entityId);
		log.setBeforeData(write(redactor.redactMap(before)));
		log.setAfterData(write(redactor.redactMap(after)));
		log.setMetadataJson(write(redactor.redactMap(metadata)));
		log.setSource(source);
		log.setRequestId(requestId);
		log.setIpAddress(ip);
		log.setUserAgent(truncate(userAgent, 500));
		log.setOccurredAt(LocalDateTime.now());
		return auditLogs.save(log);
	}

	private String write(Map<String, Object> value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception ex) {
			return "{\"redacted\":true}";
		}
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}
}
