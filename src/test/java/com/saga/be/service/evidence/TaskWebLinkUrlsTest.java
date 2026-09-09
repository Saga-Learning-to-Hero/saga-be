package com.saga.be.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import org.junit.jupiter.api.Test;

class TaskWebLinkUrlsTest {

	@Test
	void acceptsHttpAndHttps() {
		assertEquals("https://docs.google.com/document/d/abc", TaskWebLinkUrls.normalize("  https://docs.google.com/document/d/abc "));
		assertEquals("http://localhost:3000/spec", TaskWebLinkUrls.normalize("http://localhost:3000/spec"));
	}

	@Test
	void rejectsNonHttpSchemes() {
		AcademicException ex =
				assertThrows(AcademicException.class, () -> TaskWebLinkUrls.normalize("javascript:alert(1)"));
		assertEquals(AcademicErrorCode.TASK_WEB_LINK_INVALID, ex.getCode());
	}

	@Test
	void rejectsMissingHost() {
		AcademicException ex = assertThrows(AcademicException.class, () -> TaskWebLinkUrls.normalize("https://"));
		assertEquals(AcademicErrorCode.TASK_WEB_LINK_INVALID, ex.getCode());
	}

	@Test
	void hashIsStableForNormalizedUrl() {
		String url = TaskWebLinkUrls.normalize("https://example.com/a");
		assertEquals(TaskWebLinkUrls.hash(url), TaskWebLinkUrls.hash(url));
		assertEquals(64, TaskWebLinkUrls.hash(url).length());
	}
}
