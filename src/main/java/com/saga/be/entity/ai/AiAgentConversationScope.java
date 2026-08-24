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
	name = "ai_agent_conversation_scope",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_ai_conversation_id", columnNames = {"conversation_id"})
	}
)
public class AiAgentConversationScope extends BaseEntity {

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "conversation_id", columnDefinition = "char(36)", nullable = false)
	private UUID conversationId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_user_id", nullable = false)
	private UserAccount ownerUser;

	@Enumerated(EnumType.STRING)
	@Column(name = "owner_application_role", length = 32, nullable = false)
	private AccountRole ownerApplicationRole;
}
