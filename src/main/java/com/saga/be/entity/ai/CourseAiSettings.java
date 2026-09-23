package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row per course, created lazily on first write. Absence means the safe defaults apply:
 * automation OFF, platform fallback OFF -- a course is never silently opted into spending money. */
@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_course_settings", uniqueConstraints = @UniqueConstraint(name = "uk_ai_course_settings_course", columnNames = "course_id"))
public class CourseAiSettings extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "course_id", nullable = false) private Course course;
	@Column(name = "automation_enabled", nullable = false) private boolean automationEnabled;
	@Column(name = "allow_platform_fallback", nullable = false) private boolean allowPlatformFallback;
}
