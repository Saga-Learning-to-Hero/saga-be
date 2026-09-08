package com.saga.be.service.team;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

public final class LecturerTeamWorkbook {

	public static final String SHEET_NAME = "Team_Assignment";
	public static final String INSTRUCTION_SHEET = "Huong_Dan";
	/** Hidden sheet backing the TeamRole dropdown (avoids Excel treating "Leader,Member" as one choice). */
	public static final String ROLE_LIST_SHEET = "_TeamRoleList";
	public static final String ROLE_LIST_NAME = "TeamRoles";
	public static final List<String> HEADERS =
			List.of("No", "Class", "FullName", "StudentCode", "Email", "TeamNo", "TeamName", "TeamRole");
	public static final String ROLE_LEADER = "Leader";
	public static final String ROLE_MEMBER = "Member";

	private LecturerTeamWorkbook() {}

	public static byte[] template(String classCode, List<TemplateRow> students) {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			XSSFSheet sheet = workbook.createSheet(SHEET_NAME);
			XSSFCellStyle headerStyle = workbook.createCellStyle();
			XSSFFont font = workbook.createFont();
			font.setBold(true);
			headerStyle.setFont(font);
			XSSFRow header = sheet.createRow(0);
			for (int i = 0; i < HEADERS.size(); i++) {
				header.createCell(i).setCellValue(HEADERS.get(i));
				header.getCell(i).setCellStyle(headerStyle);
			}
			List<TemplateRow> rows = students == null ? List.of() : students;
			for (int i = 0; i < rows.size(); i++) {
				TemplateRow student = rows.get(i);
				XSSFRow row = sheet.createRow(i + 1);
				row.createCell(0).setCellValue(i + 1);
				row.createCell(1).setCellValue(text(student.classCode()));
				row.createCell(2).setCellValue(text(student.fullName()));
				row.createCell(3).setCellValue(text(student.studentCode()));
				row.createCell(4).setCellValue(text(student.email()));
				if (student.teamNo() != null) {
					row.createCell(5).setCellValue(student.teamNo());
				} else {
					row.createCell(5).setBlank();
				}
				row.createCell(6).setCellValue(text(student.teamName()));
				row.createCell(7).setCellValue(text(student.teamRole()));
			}
			sheet.createFreezePane(0, 1);
			sheet.setColumnWidth(0, 8 * 256);
			sheet.setColumnWidth(1, 14 * 256);
			sheet.setColumnWidth(2, 28 * 256);
			sheet.setColumnWidth(3, 16 * 256);
			sheet.setColumnWidth(4, 32 * 256);
			sheet.setColumnWidth(5, 12 * 256);
			sheet.setColumnWidth(6, 22 * 256);
			sheet.setColumnWidth(7, 14 * 256);
			int lastDataRow = Math.max(1, rows.size());
			sheet.setAutoFilter(new CellRangeAddress(0, lastDataRow, 0, HEADERS.size() - 1));
			addTeamRoleDropdown(workbook, sheet, rows.size());
			helpNote(workbook, classCode == null ? "" : classCode);
			workbook.setActiveSheet(workbook.getSheetIndex(sheet));
			workbook.write(out);
			return out.toByteArray();
		} catch (IOException ex) {
			throw new AcademicException(
					AcademicErrorCode.TEAM_FILE_INVALID, HttpStatus.BAD_REQUEST, "Unable to build team template.");
		}
	}

	/**
	 * Excel list validation via createExplicitListConstraint joins values with commas into one formula
	 * ({@code "Leader,Member"}). Some Excel builds treat that as a single invalid choice. A hidden-sheet
	 * named range yields two distinct dropdown items without relying on locale list separators.
	 */
	private static void addTeamRoleDropdown(XSSFWorkbook workbook, XSSFSheet sheet, int studentCount) {
		XSSFSheet roleList = workbook.createSheet(ROLE_LIST_SHEET);
		roleList.createRow(0).createCell(0).setCellValue(ROLE_LEADER);
		roleList.createRow(1).createCell(0).setCellValue(ROLE_MEMBER);
		Name named = workbook.createName();
		named.setNameName(ROLE_LIST_NAME);
		named.setRefersToFormula("'" + ROLE_LIST_SHEET + "'!$A$1:$A$2");
		workbook.setSheetHidden(workbook.getSheetIndex(roleList), true);

		DataValidationHelper helper = sheet.getDataValidationHelper();
		DataValidationConstraint constraint = helper.createFormulaListConstraint(ROLE_LIST_NAME);
		CellRangeAddressList addressList = new CellRangeAddressList(1, Math.max(500, studentCount + 50), 7, 7);
		DataValidation validation = helper.createValidation(constraint, addressList);
		validation.setSuppressDropDownArrow(true);
		validation.setShowErrorBox(true);
		validation.setEmptyCellAllowed(true);
		sheet.addValidationData(validation);
	}

	private static void helpNote(XSSFWorkbook workbook, String classCode) {
		XSSFSheet help = workbook.createSheet(INSTRUCTION_SHEET);
		help.createRow(0).createCell(0).setCellValue("Import this workbook into the same Course you downloaded it from.");
		help.createRow(1).createCell(0).setCellValue("Required sheet: " + SHEET_NAME);
		help.createRow(2)
				.createCell(0)
				.setCellValue("Identity columns (No, Class, FullName, StudentCode, Email) come from SAGA. Do not use them to inject another student.");
		help.createRow(3)
				.createCell(0)
				.setCellValue("Class must match this course class code"
						+ (StringUtils.hasText(classCode) ? " (" + classCode + ")" : "")
						+ ". No is display-only.");
		help.createRow(4).createCell(0).setCellValue("Editable columns: TeamNo, TeamName, TeamRole.");
		help.createRow(5).createCell(0).setCellValue("TeamNo is the canonical team identity in this course and must be a positive integer.");
		help.createRow(6).createCell(0).setCellValue("Rows with the same TeamNo must use the same TeamName. Duplicate names on different TeamNo values are rejected.");
		help.createRow(7).createCell(0).setCellValue("TeamRole must be Leader or Member. Each team requires exactly one Leader.");
		help.createRow(8)
				.createCell(0)
				.setCellValue("Every currently ACTIVE enrolled student must appear exactly once with a valid team assignment before confirm.");
		help.setColumnWidth(0, 110 * 256);
	}

	public static List<RawRow> parse(byte[] bytes) {
		if (bytes == null || bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
			throw invalid("Team file must be an XLSX workbook.");
		}
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Sheet sheet = workbook.getSheet(SHEET_NAME);
			if (sheet == null) {
				throw invalid("Missing required sheet " + SHEET_NAME + ".");
			}
			Row header = sheet.getRow(0);
			if (header == null || !headersMatch(header)) {
				throw invalid("Team header must be No, Class, FullName, StudentCode, Email, TeamNo, TeamName, TeamRole.");
			}
			DataFormatter formatter = new DataFormatter();
			List<RawRow> rows = new ArrayList<>();
			int last = sheet.getLastRowNum();
			for (int i = 1; i <= last; i++) {
				Row row = sheet.getRow(i);
				if (row == null || isEmpty(row, formatter)) {
					continue;
				}
				rows.add(new RawRow(
						i + 1,
						cell(row, 0, formatter),
						cell(row, 1, formatter),
						cell(row, 2, formatter),
						cell(row, 3, formatter),
						cell(row, 4, formatter),
						cell(row, 5, formatter),
						cell(row, 6, formatter),
						cell(row, 7, formatter)));
			}
			return rows;
		} catch (AcademicException ex) {
			throw ex;
		} catch (Exception ex) {
			throw invalid("Team workbook is malformed.");
		}
	}

	private static boolean headersMatch(Row header) {
		DataFormatter formatter = new DataFormatter();
		for (int i = 0; i < HEADERS.size(); i++) {
			String actual = cell(header, i, formatter).replace(" ", "");
			if (!HEADERS.get(i).equalsIgnoreCase(actual)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isEmpty(Row row, DataFormatter formatter) {
		for (int i = 0; i < HEADERS.size(); i++) {
			if (StringUtils.hasText(cell(row, i, formatter))) {
				return false;
			}
		}
		return true;
	}

	private static String cell(Row row, int index, DataFormatter formatter) {
		Cell cell = row.getCell(index);
		if (cell == null) {
			return "";
		}
		if (cell.getCellType() == CellType.FORMULA) {
			return formatter.formatCellValue(cell).trim();
		}
		return formatter.formatCellValue(cell).trim();
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.TEAM_FILE_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	public record TemplateRow(
			String classCode,
			String fullName,
			String studentCode,
			String email,
			Integer teamNo,
			String teamName,
			String teamRole) {}

	public record RawRow(
			int rowNumber,
			String no,
			String classCode,
			String fullName,
			String studentCode,
			String email,
			String teamNo,
			String teamName,
			String teamRole) {}
}
