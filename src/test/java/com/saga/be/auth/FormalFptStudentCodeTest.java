package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FormalFptStudentCodeTest {

	@Test
	void extractsTrailingFormalCodeFromInstitutionalEmails() {
		assertEquals("SE183904", FormalFptStudentCode.extractFromEmail("hailhse183904@fpt.edu.vn").orElseThrow());
		assertEquals("SE171184", FormalFptStudentCode.extractFromEmail("minhbpnse171184@fpt.edu.vn").orElseThrow());
		assertEquals("AI123456", FormalFptStudentCode.extractFromEmail("abcai123456@fpt.edu.vn").orElseThrow());
		assertEquals("IA654321", FormalFptStudentCode.extractFromEmail("xynia654321@fpt.edu.vn").orElseThrow());
		assertEquals("SE170506", FormalFptStudentCode.extractFromEmail("trungtdse170506@fpt.edu.vn").orElseThrow());
	}

	@Test
	void normalizesMixedCaseSuffix() {
		assertEquals("SE183904", FormalFptStudentCode.extractFromEmail("HaiLHSE183904@fpt.edu.vn").orElseThrow());
		assertEquals("AI123456", FormalFptStudentCode.extractFromEmail("AbCaI123456@fpt.edu.vn").orElseThrow());
	}

	@Test
	void acceptsAlreadyCodeShapedLocalPart() {
		assertEquals("SE170506", FormalFptStudentCode.extractFromEmail("SE170506@fpt.edu.vn").orElseThrow());
		assertEquals("IA654321", FormalFptStudentCode.extractFromEmail("ia654321@fpt.edu.vn").orElseThrow());
	}

	@Test
	void rejectsInvalidSuffix() {
		assertTrue(FormalFptStudentCode.extractFromEmail("a123456@fpt.edu.vn").isEmpty());
		assertTrue(FormalFptStudentCode.extractFromEmail("hailhse18390@fpt.edu.vn").isEmpty());
		assertTrue(FormalFptStudentCode.extractFromEmail("hailhse183904a@fpt.edu.vn").isEmpty());
		assertTrue(FormalFptStudentCode.extractFromEmail("nodigits@fpt.edu.vn").isEmpty());
	}

	@Test
	void requiresSuffixAnchoredAtEndOfLocalPart() {
		assertTrue(FormalFptStudentCode.extractFromEmail("se170506abc@fpt.edu.vn").isEmpty());
		assertTrue(FormalFptStudentCode.extractFromEmail("ai123456xyz@fpt.edu.vn").isEmpty());
	}

	@Test
	void normalizeFormalCodeUppercasesValidCodesOnly() {
		assertEquals("SE183904", FormalFptStudentCode.normalizeFormalCode("se183904").orElseThrow());
		assertEquals("AI123456", FormalFptStudentCode.normalizeFormalCode("ai123456").orElseThrow());
		assertTrue(FormalFptStudentCode.normalizeFormalCode("hailhse183904").isEmpty());
		assertTrue(FormalFptStudentCode.normalizeFormalCode("SE18390").isEmpty());
	}
}
