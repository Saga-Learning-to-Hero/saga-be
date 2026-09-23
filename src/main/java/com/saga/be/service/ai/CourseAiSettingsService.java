package com.saga.be.service.ai;

import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.CourseAiSettings;
import com.saga.be.repository.CourseAiSettingsRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Absence of a row IS the safe default (automation OFF, platform fallback OFF) -- read paths
 * never materialize a row just to answer a GET, so a course a lecturer never touched costs nothing. */
@Service @Profile("!test")
public class CourseAiSettingsService {
	private final CourseAiSettingsRepository settings;

	public CourseAiSettingsService(CourseAiSettingsRepository settings) { this.settings = settings; }

	public record Settings(boolean automationEnabled, boolean allowPlatformFallback) {
		static final Settings SAFE_DEFAULT = new Settings(false, false);
	}

	@Transactional(readOnly = true)
	public Settings get(java.util.UUID courseId) {
		return settings.findByCourse_Id(courseId).map(row -> new Settings(row.isAutomationEnabled(), row.isAllowPlatformFallback())).orElse(Settings.SAFE_DEFAULT);
	}

	@Transactional
	public Settings update(Course course, boolean automationEnabled, boolean allowPlatformFallback) {
		CourseAiSettings row = settings.findByCourse_Id(course.getId()).orElseGet(() -> { CourseAiSettings created = new CourseAiSettings(); created.setCourse(course); return created; });
		row.setAutomationEnabled(automationEnabled);
		row.setAllowPlatformFallback(allowPlatformFallback);
		settings.save(row);
		return new Settings(automationEnabled, allowPlatformFallback);
	}
}
