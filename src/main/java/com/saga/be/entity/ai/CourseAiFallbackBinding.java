package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.AiProvider;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One entry of a course's ordered PRIMARY fallback chain (attempt order 1..3). Only ever tried
 * with the course's own PRIMARY credential for that provider -- never a platform credential. */
@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_course_fallback_binding", uniqueConstraints = {
		@UniqueConstraint(name = "uk_ai_course_fallback_binding_order", columnNames = {"course_id", "attempt_order"}),
		@UniqueConstraint(name = "uk_ai_course_fallback_binding_model", columnNames = {"course_id", "provider", "model_id"})})
public class CourseAiFallbackBinding extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "course_id", nullable = false) private Course course;
	@Column(name = "attempt_order", nullable = false) private int attemptOrder;
	@Enumerated(EnumType.STRING) @Column(name = "provider", length = 32, nullable = false) private AiProvider provider;
	@Column(name = "model_id", length = 128, nullable = false) private String modelId;
}
