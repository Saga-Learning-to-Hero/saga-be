package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "ai_agent_delegation_context",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_ai_delegation_token", columnNames = {"token_hash"})
	}
)
public class AiAgentDelegationContext extends BaseEntity {

	@Column(name = "token_hash", length = 64, nullable = false)
	private String tokenHash;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "conversation_id", columnDefinition = "char(36)", nullable = false)
	private UUID conversationId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "actor_user_id", nullable = false)
	private UserAccount actorUser;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor_application_role", length = 32, nullable = false)
	private AccountRole actorApplicationRole;

	@Column(name = "capabilities", length = 128, nullable = false)
	private String capabilities;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "course_id", nullable = true)
	private Course course;
}
