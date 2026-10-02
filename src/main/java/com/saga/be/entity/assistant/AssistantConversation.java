package com.saga.be.entity.assistant;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One user's assistant conversation about one project. Only its owner can read or continue it. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
		name = "assistant_conversation",
		indexes = @Index(name = "ix_assistant_conversation_owner", columnList = "user_account_id, project_id, last_message_at"))
public class AssistantConversation extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_account_id", nullable = false)
	private UserAccount userAccount;

	/** The start of the first question, set when it is asked. */
	@Column(name = "title", length = 200)
	private String title;

	@Column(name = "last_message_at")
	private LocalDateTime lastMessageAt;
}
