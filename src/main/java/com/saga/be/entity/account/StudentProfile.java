package com.saga.be.entity.account;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "student_profile",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_student_profile_user", columnNames = {"user_account_id"}),
		@UniqueConstraint(name = "uk_student_profile_code", columnNames = {"student_code"})
	}
)
public class StudentProfile extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_account_id", nullable = false)
	private UserAccount userAccount;

	@Column(name = "student_code", length = 64)
	private String studentCode;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "approved_by_user_id", nullable = true)
	private UserAccount approvedBy;

	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}
