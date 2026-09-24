package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.AiProvider;
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
	/** NULL provider+model = legacy binding: the OpenAI course credential with the platform model. */
	@Enumerated(EnumType.STRING) @Column(name = "primary_provider", length = 32) private AiProvider primaryProvider;
	@Column(name = "primary_model_id", length = 128) private String primaryModelId;
	/** Course-owned PRIMARY fallback chain switch; OFF unless the lecturer opts in. */
	@Column(name = "fallback_enabled", nullable = false) private boolean fallbackEnabled;
	@Enumerated(EnumType.STRING) @Column(name = "secondary_provider", length = 32) private AiProvider secondaryProvider;
	@Column(name = "secondary_model_id", length = 128) private String secondaryModelId;
}
