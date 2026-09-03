package com.saga.be.mail;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.Properties;
import org.springframework.util.StringUtils;

final class GmailMime {

	private GmailMime() {}

	static byte[] rfc822(String from, EmailMessage message) throws Exception {
		Session session = Session.getInstance(new Properties());
		MimeMessage mime = new MimeMessage(session);
		mime.setFrom(new InternetAddress(from, false));
		mime.setRecipient(Message.RecipientType.TO, new InternetAddress(message.to().trim(), false));
		mime.setSubject(message.subject() == null ? "" : message.subject(), "UTF-8");
		mime.setSentDate(new Date());
		boolean html = StringUtils.hasText(message.htmlBody());
		boolean text = StringUtils.hasText(message.textBody());
		if (html && text) {
			MimeMultipart multipart = new MimeMultipart("alternative");
			MimeBodyPart textPart = new MimeBodyPart();
			textPart.setText(message.textBody(), "UTF-8");
			MimeBodyPart htmlPart = new MimeBodyPart();
			htmlPart.setContent(message.htmlBody(), "text/html; charset=UTF-8");
			multipart.addBodyPart(textPart);
			multipart.addBodyPart(htmlPart);
			mime.setContent(multipart);
		} else if (html) {
			mime.setContent(message.htmlBody(), "text/html; charset=UTF-8");
		} else {
			mime.setText(text ? message.textBody() : "", "UTF-8");
		}
		mime.saveChanges();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		mime.writeTo(out);
		return out.toByteArray();
	}
}
