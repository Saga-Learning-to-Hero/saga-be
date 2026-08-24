package com.saga.be.entity.academic;

import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "active_semester_setting")
public class ActiveSemesterSetting {

	@Id
	@Column(name = "singleton_id", nullable = false)
	private Byte singletonId;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "semester_id", nullable = true)
	private Semester semester;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "updated_by_user_id", nullable = true)
	private UserAccount updatedBy;
}
