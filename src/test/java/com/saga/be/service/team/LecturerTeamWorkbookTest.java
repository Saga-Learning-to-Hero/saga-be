package com.saga.be.service.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LecturerTeamWorkbookTest {

	@Test
	void templateUsesExpectedSheetHeadersDropdownAndPrefill() throws Exception {
		byte[] bytes = LecturerTeamWorkbook.template(
				"SE1705",
				List.of(new LecturerTeamWorkbook.TemplateRow(
						"SE1705", "Nguyễn Văn Ánh", "SE123456", "anh@gmail.com", 1, "Alpha", "Leader")));
		assertTrue(bytes[0] == 'P' && bytes[1] == 'K');
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			assertEquals(LecturerTeamWorkbook.SHEET_NAME, workbook.getSheetAt(0).getSheetName());
			assertEquals(LecturerTeamWorkbook.INSTRUCTION_SHEET, workbook.getSheetAt(1).getSheetName());
			Sheet sheet = workbook.getSheet(LecturerTeamWorkbook.SHEET_NAME);
			Row header = sheet.getRow(0);
			assertEquals("No", header.getCell(0).getStringCellValue());
			assertEquals("Class", header.getCell(1).getStringCellValue());
			assertEquals("FullName", header.getCell(2).getStringCellValue());
			assertEquals("StudentCode", header.getCell(3).getStringCellValue());
			assertEquals("Email", header.getCell(4).getStringCellValue());
			assertEquals("TeamNo", header.getCell(5).getStringCellValue());
			assertEquals("TeamName", header.getCell(6).getStringCellValue());
			assertEquals("TeamRole", header.getCell(7).getStringCellValue());
			Row data = sheet.getRow(1);
			assertEquals("SE1705", data.getCell(1).getStringCellValue());
			assertEquals("Nguyễn Văn Ánh", data.getCell(2).getStringCellValue());
			assertEquals("SE123456", data.getCell(3).getStringCellValue());
			assertEquals("anh@gmail.com", data.getCell(4).getStringCellValue());
			assertEquals(1, (int) data.getCell(5).getNumericCellValue());
			assertEquals("Alpha", data.getCell(6).getStringCellValue());
			assertEquals("Leader", data.getCell(7).getStringCellValue());
			assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition());
			boolean hasRoleList = false;
			for (DataValidation validation : sheet.getDataValidations()) {
				if (validation.getValidationConstraint().getExplicitListValues() != null
						&& List.of(validation.getValidationConstraint().getExplicitListValues())
								.contains("Leader")) {
					hasRoleList = true;
				}
			}
			assertTrue(hasRoleList);
			assertTrue(workbook.getSheet(LecturerTeamWorkbook.INSTRUCTION_SHEET)
					.getRow(5)
					.getCell(0)
					.getStringCellValue()
					.contains("positive integer"));
			assertTrue(workbook.getSheet(LecturerTeamWorkbook.INSTRUCTION_SHEET)
					.getRow(7)
					.getCell(0)
					.getStringCellValue()
					.contains("exactly one Leader"));
		}
	}

	@Test
	void malformedAndNonXlsxAreRejected() {
		AcademicException csv = assertThrows(
				AcademicException.class,
				() -> LecturerTeamWorkbook.parse("No,Class\n1,SE1705".getBytes(StandardCharsets.UTF_8)));
		assertEquals(AcademicErrorCode.TEAM_FILE_INVALID, csv.getCode());
		assertEquals(HttpStatus.BAD_REQUEST, csv.getStatus());
	}

	@Test
	void missingSheetAndBadHeadersAreRejected() throws Exception {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			workbook.createSheet("Wrong");
			workbook.write(out);
			AcademicException missing = assertThrows(AcademicException.class, () -> LecturerTeamWorkbook.parse(out.toByteArray()));
			assertEquals(AcademicErrorCode.TEAM_FILE_INVALID, missing.getCode());
		}
		byte[] template = LecturerTeamWorkbook.template("SE1705", List.of());
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			workbook.getSheet(LecturerTeamWorkbook.SHEET_NAME).getRow(0).getCell(5).setCellValue("GroupNo");
			workbook.write(out);
			AcademicException headers = assertThrows(AcademicException.class, () -> LecturerTeamWorkbook.parse(out.toByteArray()));
			assertEquals(AcademicErrorCode.TEAM_FILE_INVALID, headers.getCode());
		}
	}

	static byte[] filledWorkbook(String classCode, List<String[]> dataRows) throws Exception {
		byte[] template = LecturerTeamWorkbook.template(classCode, List.of());
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.getSheet(LecturerTeamWorkbook.SHEET_NAME);
			for (int i = 0; i < dataRows.size(); i++) {
				String[] cells = dataRows.get(i);
				Row row = sheet.createRow(i + 1);
				for (int c = 0; c < cells.length; c++) {
					row.createCell(c).setCellValue(cells[c]);
				}
			}
			workbook.write(out);
			return out.toByteArray();
		}
	}
}
