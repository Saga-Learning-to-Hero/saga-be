package com.saga.be.entity.audit;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AuditSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "audit_log",
	indexes = {
		@Index(name = "ix_audit_actor_time", columnList = "actor_user_id, occurred_at"),
		@Index(name = "ix_audit_project_time", columnList = "context_project_id, occurred_at"),
		@Index(name = "ix_audit_action_time", columnList = "action, occurred_at")
	}
)
public class AuditLog extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "actor_user_id")
	private UserAccount actorUser;

	@Column(name = "actor_full_name_snapshot", length = 255)
	private String actorFullNameSnapshot;

	@Column(name = "actor_role_snapshot", length = 32)
	private String actorRoleSnapshot;

	@Column(name = "actor_email_snapshot", length = 255)
	private String actorEmailSnapshot;

	@Column(name = "actor_student_code_snapshot", length = 64)
	private String actorStudentCodeSnapshot;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "context_class_id", columnDefinition = "char(36)")
	private UUID contextClassId;

	@Column(name = "context_class_code_snapshot", length = 64)
	private String contextClassCodeSnapshot;

	@Column(name = "context_class_name_snapshot", length = 255)
	private String contextClassNameSnapshot;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "context_course_id", columnDefinition = "char(36)")
	private UUID contextCourseId;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "context_team_id", columnDefinition = "char(36)")
	private UUID contextTeamId;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "context_project_id", columnDefinition = "char(36)")
	private UUID contextProjectId;

	@Column(name = "action", length = 64, nullable = false)
	private String action;

	@Column(name = "entity_type", length = 64, nullable = false)
	private String entityType;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "entity_id", columnDefinition = "char(36)")
	private UUID entityId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "before_data", columnDefinition = "json")
	private String beforeData;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "after_data", columnDefinition = "json")
	private String afterData;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "metadata_json", columnDefinition = "json")
	private String metadataJson;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", length = 32, nullable = false)
	private AuditSource source;

	@Column(name = "request_id", length = 64)
	private String requestId;

	@Column(name = "ip_address", length = 64)
	private String ipAddress;

	@Column(name = "user_agent", length = 500)
	private String userAgent;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;
}
