package com.saga.be.mail.template;

public record EmailTemplateModel(
		String fullName,
		String recipientEmail,
		String courseName,
		String courseCode,
		String classCode,
		String semesterCode,
		String semesterName,
		boolean institutional) {

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
				institutional);
	}
}
