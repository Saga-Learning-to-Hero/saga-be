package com.saga.be.service.roster;

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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

public final class CourseRosterWorkbook {

	public static final String SHEET_NAME = "Danh_Sach_SV";
	public static final String INSTRUCTION_SHEET = "Huong_Dan";
	public static final List<String> HEADERS = List.of("No", "Class", "FullName", "StudentCode", "Email", "MemberCode");

	private CourseRosterWorkbook() {}

	public static byte[] template(String classCode) {
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
			sheet.createFreezePane(0, 1);
			sheet.setColumnWidth(0, 8 * 256);
			sheet.setColumnWidth(1, 14 * 256);
			sheet.setColumnWidth(2, 28 * 256);
			sheet.setColumnWidth(3, 16 * 256);
			sheet.setColumnWidth(4, 32 * 256);
			sheet.setColumnWidth(5, 16 * 256);
			sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, HEADERS.size() - 1));
			helpNote(workbook, classCode == null ? "" : classCode);
			workbook.write(out);
			return out.toByteArray();
		} catch (IOException ex) {
			throw new AcademicException(AcademicErrorCode.ROSTER_FILE_INVALID, HttpStatus.BAD_REQUEST, "Unable to build roster template.");
		}
	}

	private static void helpNote(XSSFWorkbook workbook, String classCode) {
		XSSFSheet help = workbook.createSheet(INSTRUCTION_SHEET);
		help.createRow(0).createCell(0).setCellValue("Import this workbook into the same Course you downloaded it from.");
		help.createRow(1).createCell(0).setCellValue("Required sheet: " + SHEET_NAME);
		help.createRow(2).createCell(0).setCellValue("Required columns: No, Class, FullName, StudentCode, Email, MemberCode");
		help.createRow(3)
				.createCell(0)
				.setCellValue("Class must match this course class code"
						+ (StringUtils.hasText(classCode) ? " (" + classCode + ")" : "")
						+ ". No is display-only.");
		help.createRow(4).createCell(0).setCellValue("MemberCode is optional and is not stored as its own database column.");
		help.setColumnWidth(0, 80 * 256);
	}

	public static List<RawRow> parse(byte[] bytes) {
		if (bytes == null || bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
			throw invalid("Roster file must be an XLSX workbook.");
		}
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Sheet sheet = workbook.getSheet(SHEET_NAME);
			if (sheet == null) {
				throw invalid("Missing required sheet " + SHEET_NAME + ".");
			}
			Row header = sheet.getRow(0);
			if (header == null || !headersMatch(header)) {
				throw invalid("Roster header must be No, Class, FullName, StudentCode, Email, MemberCode.");
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
						cell(row, 5, formatter)));
			}
			return rows;
		} catch (AcademicException ex) {
			throw ex;
		} catch (Exception ex) {
			throw invalid("Roster workbook is malformed.");
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

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.ROSTER_FILE_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	public record RawRow(
			int rowNumber, String no, String classCode, String fullName, String studentCode, String email, String memberCode) {}
}
