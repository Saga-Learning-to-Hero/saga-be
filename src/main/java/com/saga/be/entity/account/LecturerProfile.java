package com.saga.be.entity.account;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "lecturer_profile",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_lecturer_profile_user", columnNames = {"user_account_id"})
	}
)
public class LecturerProfile extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_account_id", nullable = false)
	private UserAccount userAccount;
}
