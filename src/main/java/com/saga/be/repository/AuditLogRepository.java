package com.saga.be.repository;

import com.saga.be.entity.audit.AuditLog;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

	@Query(
			value =
					"""
					select new com.saga.be.repository.AdminAuditLogQueryRow(
							a.id, actor.id, a.actorFullNameSnapshot, a.actorRoleSnapshot,
							a.actorEmailSnapshot, a.actorStudentCodeSnapshot, a.contextClassId,
							a.contextClassCodeSnapshot, a.contextClassNameSnapshot, a.contextCourseId,
							a.contextTeamId, a.contextProjectId, a.action, a.entityType, a.entityId,
							a.beforeData, a.afterData, a.metadataJson, a.source, a.requestId,
							a.ipAddress, a.userAgent, a.occurredAt)
					from AuditLog a
					left join a.actorUser actor
					where (:actorUserId is null or actor.id = :actorUserId)
					  and (:action is null or a.action = :action)
					  and (:entityType is null or a.entityType = :entityType)
					  and (:entityId is null or a.entityId = :entityId)
					  and (:from is null or a.occurredAt >= :from)
					  and (:to is null or a.occurredAt <= :to)
					order by a.occurredAt desc, a.id desc
					""",
			countQuery =
					"""
					select count(a.id)
					from AuditLog a
					left join a.actorUser actor
					where (:actorUserId is null or actor.id = :actorUserId)
					  and (:action is null or a.action = :action)
					  and (:entityType is null or a.entityType = :entityType)
					  and (:entityId is null or a.entityId = :entityId)
					  and (:from is null or a.occurredAt >= :from)
					  and (:to is null or a.occurredAt <= :to)
					""")
	Page<AdminAuditLogQueryRow> searchAdminAuditLogs(
			@Param("actorUserId") UUID actorUserId,
			@Param("action") String action,
			@Param("entityType") String entityType,
			@Param("entityId") UUID entityId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			Pageable pageable);
}
