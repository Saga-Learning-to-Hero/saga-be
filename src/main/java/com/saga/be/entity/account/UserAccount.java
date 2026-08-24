package com.saga.be.entity.account;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "passwordHash")
@JsonIgnoreProperties({"passwordHash", "password_hash"})
@Entity
@Table(
	name = "user_account",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_user_account_email", columnNames = {"email"})
	},
	indexes = {
		@Index(name = "ix_user_account_role_status", columnList = "account_role, account_status")
	}
)
public class UserAccount extends BaseEntity {

	@Column(name = "email", length = 255, nullable = false)
	private String email;

	@Column(name = "full_name", length = 255)
	private String fullName;

	@Column(name = "avatar_url", length = 500)
	private String avatarUrl;

	@JsonIgnore
	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_role", length = 32, nullable = false)
	private AccountRole accountRole;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_status", length = 32, nullable = false)
	private AccountStatus accountStatus;
}
