package com.saga.be.service.roster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CourseRosterWorkbookTest {

	@Test
	void templateUsesExpectedSheetAndHeaders() throws Exception {
		byte[] bytes = CourseRosterWorkbook.template("SE1705");
		assertTrue(bytes[0] == 'P' && bytes[1] == 'K');
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			assertEquals(CourseRosterWorkbook.SHEET_NAME, workbook.getSheetAt(0).getSheetName());
			assertEquals(CourseRosterWorkbook.INSTRUCTION_SHEET, workbook.getSheetAt(1).getSheetName());
			Sheet sheet = workbook.getSheet(CourseRosterWorkbook.SHEET_NAME);
			Row header = sheet.getRow(0);
			assertEquals("No", header.getCell(0).getStringCellValue());
			assertEquals("Class", header.getCell(1).getStringCellValue());
			assertEquals("FullName", header.getCell(2).getStringCellValue());
			assertEquals("StudentCode", header.getCell(3).getStringCellValue());
			assertEquals("Email", header.getCell(4).getStringCellValue());
			assertEquals("MemberCode", header.getCell(5).getStringCellValue());
			assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition());
			assertTrue(workbook.getSheet(CourseRosterWorkbook.INSTRUCTION_SHEET)
					.getRow(3)
					.getCell(0)
					.getStringCellValue()
					.contains("SE1705"));
		}
	}

	@Test
	void parseSupportsUnicodeNames() throws Exception {
		byte[] bytes = filledWorkbook(
				"SE1705", List.<String[]>of(new String[] {"1", "SE1705", "Nguyễn Văn Ánh", "SE123456", "anh@gmail.com", "M1"}));
		List<CourseRosterWorkbook.RawRow> rows = CourseRosterWorkbook.parse(bytes);
		assertEquals(1, rows.size());
		assertEquals("Nguyễn Văn Ánh", rows.getFirst().fullName());
		assertEquals("anh@gmail.com", rows.getFirst().email());
		assertEquals("M1", rows.getFirst().memberCode());
	}

	@Test
	void malformedAndNonXlsxAreRejected() {
		AcademicException csv = assertThrows(
				AcademicException.class, () -> CourseRosterWorkbook.parse("No,Class\n1,SE1705".getBytes(StandardCharsets.UTF_8)));
		assertEquals(AcademicErrorCode.ROSTER_FILE_INVALID, csv.getCode());
		assertEquals(HttpStatus.BAD_REQUEST, csv.getStatus());

		AcademicException junk = assertThrows(AcademicException.class, () -> CourseRosterWorkbook.parse(new byte[] {'P', 'K', 1, 2, 3}));
		assertEquals(AcademicErrorCode.ROSTER_FILE_INVALID, junk.getCode());
	}

	@Test
	void missingSheetIsRejected() throws Exception {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			workbook.createSheet("Wrong");
			workbook.write(out);
			AcademicException ex = assertThrows(AcademicException.class, () -> CourseRosterWorkbook.parse(out.toByteArray()));
			assertEquals(AcademicErrorCode.ROSTER_FILE_INVALID, ex.getCode());
		}
	}

	static byte[] filledWorkbook(String classCode, List<String[]> dataRows) throws Exception {
		byte[] template = CourseRosterWorkbook.template(classCode);
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.getSheet(CourseRosterWorkbook.SHEET_NAME);
			int rowNum = 1;
			for (String[] values : dataRows) {
				Row row = sheet.createRow(rowNum++);
				for (int i = 0; i < values.length; i++) {
					row.createCell(i).setCellValue(values[i]);
				}
			}
			workbook.write(out);
			return out.toByteArray();
		}
	}
}
