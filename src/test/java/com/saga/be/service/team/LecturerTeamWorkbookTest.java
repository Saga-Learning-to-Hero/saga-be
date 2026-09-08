package com.saga.be.service.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.Name;
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
			assertNotNull(workbook.getSheet(LecturerTeamWorkbook.SHEET_NAME));
			assertNotNull(workbook.getSheet(LecturerTeamWorkbook.INSTRUCTION_SHEET));
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
			assertTeamRoleDropdownHasTwoDistinctChoices(workbook, sheet);
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
	void teamRoleDropdownHasExactlyLeaderAndMemberChoices() throws Exception {
		byte[] bytes = LecturerTeamWorkbook.template("SE1705", List.of());
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Sheet sheet = workbook.getSheet(LecturerTeamWorkbook.SHEET_NAME);
			List<String> choices = assertTeamRoleDropdownHasTwoDistinctChoices(workbook, sheet);
			assertEquals(LecturerTeamWorkbook.ROLE_LEADER, choices.get(0));
			assertEquals(LecturerTeamWorkbook.ROLE_MEMBER, choices.get(1));
			assertFalse(choices.contains("Leader,Member"));
			assertEquals(1, choices.stream().filter(LecturerTeamWorkbook.ROLE_LEADER::equals).count());
			assertEquals(1, choices.stream().filter(LecturerTeamWorkbook.ROLE_MEMBER::equals).count());
		}
	}

	@Test
	void prefillLeavesUnassignedRoleBlankAndKeepsAssignedRolesExact() throws Exception {
		byte[] bytes = LecturerTeamWorkbook.template(
				"SE1705",
				List.of(
						new LecturerTeamWorkbook.TemplateRow(
								"SE1705", "Leader Student", "SE111111", "leader@gmail.com", 1, "Alpha", "Leader"),
						new LecturerTeamWorkbook.TemplateRow(
								"SE1705", "Member Student", "SE222222", "member@gmail.com", 1, "Alpha", "Member"),
						new LecturerTeamWorkbook.TemplateRow(
								"SE1705", "Unassigned Student", "SE333333", "free@gmail.com", null, null, null)));
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Sheet sheet = workbook.getSheet(LecturerTeamWorkbook.SHEET_NAME);
			assertEquals("Leader", sheet.getRow(1).getCell(7).getStringCellValue());
			assertEquals("Member", sheet.getRow(2).getCell(7).getStringCellValue());
			assertTrue(isBlankCell(sheet.getRow(3).getCell(5)));
			assertTrue(isBlankCell(sheet.getRow(3).getCell(6)));
			assertTrue(isBlankCell(sheet.getRow(3).getCell(7)));
			assertFalse("Leader,Member".equals(cellText(sheet.getRow(3).getCell(7))));
			assertTeamRoleDropdownHasTwoDistinctChoices(workbook, sheet);
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

	private static List<String> assertTeamRoleDropdownHasTwoDistinctChoices(XSSFWorkbook workbook, Sheet sheet) {
		Sheet roleList = workbook.getSheet(LecturerTeamWorkbook.ROLE_LIST_SHEET);
		assertNotNull(roleList);
		assertTrue(workbook.isSheetHidden(workbook.getSheetIndex(roleList)));
		assertEquals(LecturerTeamWorkbook.ROLE_LEADER, roleList.getRow(0).getCell(0).getStringCellValue());
		assertEquals(LecturerTeamWorkbook.ROLE_MEMBER, roleList.getRow(1).getCell(0).getStringCellValue());

		Name named = workbook.getName(LecturerTeamWorkbook.ROLE_LIST_NAME);
		assertNotNull(named);
		assertTrue(named.getRefersToFormula().contains(LecturerTeamWorkbook.ROLE_LIST_SHEET));

		boolean found = false;
		for (DataValidation validation : sheet.getDataValidations()) {
			DataValidationConstraint constraint = validation.getValidationConstraint();
			if (constraint.getValidationType() != DataValidationConstraint.ValidationType.LIST) {
				continue;
			}
			String[] explicit = constraint.getExplicitListValues();
			if (explicit != null) {
				assertFalse(
						Arrays.asList(explicit).contains("Leader,Member"),
						"TeamRole must not expose combined Leader,Member as one choice");
				assertEquals(2, explicit.length);
				assertEquals(LecturerTeamWorkbook.ROLE_LEADER, explicit[0]);
				assertEquals(LecturerTeamWorkbook.ROLE_MEMBER, explicit[1]);
				found = true;
				continue;
			}
			assertEquals(LecturerTeamWorkbook.ROLE_LIST_NAME, constraint.getFormula1());
			found = true;
		}
		assertTrue(found, "TeamRole column must have list validation");
		return List.of(LecturerTeamWorkbook.ROLE_LEADER, LecturerTeamWorkbook.ROLE_MEMBER);
	}

	private static boolean isBlankCell(Cell cell) {
		if (cell == null) {
			return true;
		}
		if (cell.getCellType() == CellType.BLANK) {
			return true;
		}
		return cellText(cell).isEmpty();
	}

	private static String cellText(Cell cell) {
		if (cell == null) {
			return "";
		}
		return switch (cell.getCellType()) {
			case STRING -> cell.getStringCellValue() == null ? "" : cell.getStringCellValue().trim();
			case BLANK -> "";
			default -> String.valueOf(cell).trim();
		};
	}
}
