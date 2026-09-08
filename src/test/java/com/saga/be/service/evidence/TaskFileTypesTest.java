package com.saga.be.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TaskFileTypesTest {

	@Test
	void acceptsPdfByMagicAndExtension() {
		byte[] pdf = "%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1);
		TaskFileTypes.Accepted accepted = TaskFileTypes.accept("bao-cao.pdf", "application/pdf", pdf);
		assertEquals("bao-cao.pdf", accepted.filename());
		assertEquals("application/pdf", accepted.mimeType());
	}

	@Test
	void acceptsPngAndStripsPath() {
		byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2};
		TaskFileTypes.Accepted accepted = TaskFileTypes.accept("C:\\tmp\\wireframe.png", "image/png", png);
		assertEquals("wireframe.png", accepted.filename());
		assertEquals("image/png", accepted.mimeType());
	}

	@Test
	void acceptsOctetStreamWhenExtensionAndMagicMatch() {
		byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1};
		TaskFileTypes.Accepted accepted = TaskFileTypes.accept("photo.jpg", "application/octet-stream", jpeg);
		assertEquals("image/jpeg", accepted.mimeType());
	}

	@Test
	void acceptsPlainText() {
		TaskFileTypes.Accepted accepted =
				TaskFileTypes.accept("notes.md", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
		assertEquals("text/markdown", accepted.mimeType());
	}

	@Test
	void rejectsEmpty() {
		AcademicException ex =
				assertThrows(AcademicException.class, () -> TaskFileTypes.accept("a.pdf", "application/pdf", new byte[0]));
		assertEquals(AcademicErrorCode.TASK_FILE_INVALID, ex.getCode());
	}

	@Test
	void rejectsExecutable() {
		byte[] exe = new byte[] {'M', 'Z', 0, 0, 0, 0};
		AcademicException ex = assertThrows(AcademicException.class, () -> TaskFileTypes.accept("virus.pdf", "application/pdf", exe));
		assertEquals(AcademicErrorCode.TASK_FILE_INVALID, ex.getCode());
	}

	@Test
	void rejectsDisallowedExtension() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> TaskFileTypes.accept("script.js", "text/javascript", "x".getBytes(StandardCharsets.UTF_8)));
		assertEquals(AcademicErrorCode.TASK_FILE_INVALID, ex.getCode());
	}

	@Test
	void rejectsPdfExtensionWithWrongMagic() {
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> TaskFileTypes.accept("fake.pdf", "application/pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8)));
		assertTrue(ex.getMessage().contains("PDF"));
	}

	@Test
	void rejectsContentTypeMismatch() {
		byte[] pdf = "%PDF-1.4".getBytes(StandardCharsets.US_ASCII);
		AcademicException ex =
				assertThrows(AcademicException.class, () -> TaskFileTypes.accept("a.pdf", "image/png", pdf));
		assertEquals(AcademicErrorCode.TASK_FILE_INVALID, ex.getCode());
	}
}
