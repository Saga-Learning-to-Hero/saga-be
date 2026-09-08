package com.saga.be.service.evidence;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

public final class TaskFileTypes {

	public record Accepted(String filename, String mimeType) {}

	private static final Set<String> EXTENSIONS = Set.of(
			"pdf",
			"png",
			"jpg",
			"jpeg",
			"gif",
			"webp",
			"txt",
			"csv",
			"md",
			"doc",
			"docx",
			"xls",
			"xlsx",
			"ppt",
			"pptx");

	private static final Map<String, String> MIME_BY_EXT = Map.ofEntries(
			Map.entry("pdf", "application/pdf"),
			Map.entry("png", "image/png"),
			Map.entry("jpg", "image/jpeg"),
			Map.entry("jpeg", "image/jpeg"),
			Map.entry("gif", "image/gif"),
			Map.entry("webp", "image/webp"),
			Map.entry("txt", "text/plain"),
			Map.entry("csv", "text/csv"),
			Map.entry("md", "text/markdown"),
			Map.entry("doc", "application/msword"),
			Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
			Map.entry("xls", "application/vnd.ms-excel"),
			Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
			Map.entry("ppt", "application/vnd.ms-powerpoint"),
			Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

	private TaskFileTypes() {}

	public static Accepted accept(String originalFilename, String declaredContentType, byte[] content) {
		if (content == null || content.length == 0) {
			throw invalid("File is required.");
		}
		if (startsWith(content, "MZ") || startsWith(content, new byte[] {0x7f, 'E', 'L', 'F'})) {
			throw invalid("Executable files are not allowed.");
		}
		String filename = sanitizeFilename(originalFilename);
		String extension = extensionOf(filename);
		if (!EXTENSIONS.contains(extension)) {
			throw invalid("File type is not allowed. Use a document or image.");
		}
		String mime = MIME_BY_EXT.get(extension);
		assertMagic(extension, content);
		assertDeclaredType(declaredContentType, mime, extension);
		return new Accepted(filename, mime);
	}

	public static String sanitizeFilename(String originalFilename) {
		if (originalFilename == null || originalFilename.isBlank()) {
			throw invalid("Filename is required.");
		}
		String name = originalFilename.replace('\\', '/').trim();
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		name = name.trim();
		if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
			throw invalid("Filename is invalid.");
		}
		if (name.indexOf('\0') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
			throw invalid("Filename is invalid.");
		}
		if (name.length() > 512) {
			throw invalid("Filename must be at most 512 characters.");
		}
		return name;
	}

	private static String extensionOf(String filename) {
		int dot = filename.lastIndexOf('.');
		if (dot <= 0 || dot == filename.length() - 1) {
			throw invalid("File type is not allowed. Use a document or image.");
		}
		return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static void assertMagic(String extension, byte[] content) {
		switch (extension) {
			case "pdf" -> require(startsWith(content, "%PDF"), "File content does not match a PDF.");
			case "png" -> require(startsWith(content, new byte[] {(byte) 0x89, 'P', 'N', 'G'}), "File content does not match a PNG.");
			case "jpg", "jpeg" -> require(
					content.length >= 3
							&& content[0] == (byte) 0xFF
							&& content[1] == (byte) 0xD8
							&& content[2] == (byte) 0xFF,
					"File content does not match a JPEG.");
			case "gif" -> require(startsWith(content, "GIF87a") || startsWith(content, "GIF89a"), "File content does not match a GIF.");
			case "webp" -> require(
					startsWith(content, "RIFF") && content.length >= 12 && startsWith(content, 8, "WEBP"),
					"File content does not match a WEBP.");
			case "docx", "xlsx", "pptx" -> require(startsWith(content, "PK"), "File content does not match an Office document.");
			case "doc", "xls", "ppt" -> require(
					startsWith(content, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}),
					"File content does not match an Office document.");
			default -> {
				// txt / csv / md: no reliable magic
			}
		}
	}

	private static void assertDeclaredType(String declaredContentType, String expectedMime, String extension) {
		if (declaredContentType == null || declaredContentType.isBlank()) {
			return;
		}
		String declared = declaredContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
		if (declared.isEmpty() || "application/octet-stream".equals(declared)) {
			return;
		}
		if (declared.equals(expectedMime)) {
			return;
		}
		if (("jpg".equals(extension) || "jpeg".equals(extension)) && "image/jpg".equals(declared)) {
			return;
		}
		if ("csv".equals(extension) && ("text/plain".equals(declared) || "application/csv".equals(declared))) {
			return;
		}
		if ("md".equals(extension) && "text/plain".equals(declared)) {
			return;
		}
		throw invalid("File content type does not match the filename.");
	}

	private static boolean startsWith(byte[] content, String ascii) {
		return startsWith(content, 0, ascii);
	}

	private static boolean startsWith(byte[] content, int offset, String ascii) {
		return startsWith(content, offset, ascii.getBytes(StandardCharsets.US_ASCII));
	}

	private static boolean startsWith(byte[] content, byte[] prefix) {
		return startsWith(content, 0, prefix);
	}

	private static boolean startsWith(byte[] content, int offset, byte[] prefix) {
		if (content.length < offset + prefix.length) {
			return false;
		}
		for (int i = 0; i < prefix.length; i++) {
			if (content[offset + i] != prefix[i]) {
				return false;
			}
		}
		return true;
	}

	private static void require(boolean ok, String message) {
		if (!ok) {
			throw invalid(message);
		}
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.TASK_FILE_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}
