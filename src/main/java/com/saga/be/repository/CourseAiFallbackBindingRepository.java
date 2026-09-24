package com.saga.be.repository;

import com.saga.be.entity.ai.CourseAiFallbackBinding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseAiFallbackBindingRepository extends JpaRepository<CourseAiFallbackBinding, UUID> {
	List<CourseAiFallbackBinding> findByCourse_IdOrderByAttemptOrderAsc(UUID courseId);

	/** Bulk delete executes immediately, so a full replace can re-insert the same attempt orders in
	 * the same transaction without tripping the (course, attempt_order) unique key. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from CourseAiFallbackBinding b where b.course.id = :courseId")
	int deleteAllForCourse(@Param("courseId") UUID courseId);
}
