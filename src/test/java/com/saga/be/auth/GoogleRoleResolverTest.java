package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class GoogleRoleResolverTest {

	private final GoogleRoleResolver resolver = new GoogleRoleResolver();
	private final Set<String> allowed = Set.of("fpt.edu.vn", "fe.edu.vn");

	@Test
	void feDomainIsLecturer() {
		assertEquals(
				GoogleRoleResolver.Outcome.LECTURER,
				resolver.resolve("an@fe.edu.vn", true, "fe.edu.vn", allowed));
	}

	@Test
	void fptStudentRegexIsStudent() {
		assertEquals(
				GoogleRoleResolver.Outcome.STUDENT,
				resolver.resolve("anvse170102@fpt.edu.vn", true, "fpt.edu.vn", allowed));
	}

	@Test
	void verifiedFptNonStudentIsLecturer() {
		assertEquals(
				GoogleRoleResolver.Outcome.LECTURER,
				resolver.resolve("antv12@fpt.edu.vn", true, "fpt.edu.vn", allowed));
	}

	@Test
	void gmailIsRejected() {
		assertEquals(
				GoogleRoleResolver.Outcome.REJECT_DOMAIN,
				resolver.resolve("student@gmail.com", true, null, allowed));
	}

	@Test
	void unverifiedEmailIsRejected() {
		assertEquals(
				GoogleRoleResolver.Outcome.REJECT_UNVERIFIED,
				resolver.resolve("anvse170102@fpt.edu.vn", false, "fpt.edu.vn", allowed));
	}

	@Test
	void missingHostedDomainFailsClosed() {
		assertEquals(
				GoogleRoleResolver.Outcome.REJECT_DOMAIN,
				resolver.resolve("anvse170102@fpt.edu.vn", true, null, allowed));
	}

	@Test
	void conflictingHostedDomainFailsClosed() {
		assertEquals(
				GoogleRoleResolver.Outcome.REJECT_DOMAIN,
				resolver.resolve("anvse170102@fpt.edu.vn", true, "gmail.com", allowed));
	}
}
