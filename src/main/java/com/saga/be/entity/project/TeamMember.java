package com.saga.be.entity.project;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.project.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
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
	name = "team_member",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_team_member_enrollment", columnNames = {"team_id", "course_enrollment_id"})
	},
	indexes = {
		@Index(name = "ix_team_member_enrollment", columnList = "course_enrollment_id")
	}
)
public class TeamMember extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "team_id", nullable = false)
	private Team team;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_id", nullable = false)
	private Course course;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "course_enrollment_id", nullable = false)
	private CourseEnrollment courseEnrollment;

	@Enumerated(EnumType.STRING)
	@Column(name = "role_in_team", length = 32, nullable = false)
	private RoleInTeam roleInTeam;
}
