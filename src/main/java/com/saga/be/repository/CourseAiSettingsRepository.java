package com.saga.be.repository;

import com.saga.be.entity.ai.CourseAiSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseAiSettingsRepository extends JpaRepository<CourseAiSettings, UUID> {
	Optional<CourseAiSettings> findByCourse_Id(UUID courseId);
}
