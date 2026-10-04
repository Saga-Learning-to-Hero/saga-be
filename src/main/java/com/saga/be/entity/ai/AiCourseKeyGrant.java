package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.project.Project;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The lecturer lets this team (project) fall back to the course's AI key. No row = it may not. */
@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_course_key_grant", uniqueConstraints = @UniqueConstraint(name = "uk_ai_course_key_grant_project", columnNames = "project_id"))
public class AiCourseKeyGrant extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private Project project;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "granted_by_user_id") private UserAccount grantedBy;
}
