package com.saga.be.mail.template;

public record EmailTemplateModel(
		String fullName,
		String recipientEmail,
		String courseName,
		String courseCode,
		String classCode,
		String semesterCode,
		String semesterName,
		boolean institutional,
		Integer teamNo,
		String teamName,
		String teamRole) {

	public static EmailTemplateModel course(
			String fullName,
			String recipientEmail,
			String courseName,
			String courseCode,
			String classCode,
			String semesterCode,
			String semesterName,
			boolean institutional) {
		return new EmailTemplateModel(
				fullName,
				recipientEmail,
				courseName,
				courseCode,
				classCode,
				semesterCode,
				semesterName,
				institutional,
				null,
				null,
				null);
	}

	public static EmailTemplateModel teamAssigned(
			String fullName,
			String recipientEmail,
			String courseName,
			String courseCode,
			String classCode,
			String semesterCode,
			String semesterName,
			Integer teamNo,
			String teamName,
			String teamRole) {
		return new EmailTemplateModel(
				fullName,
				recipientEmail,
				courseName,
				courseCode,
				classCode,
				semesterCode,
				semesterName,
				false,
				teamNo,
				teamName,
				teamRole);
	}
}
