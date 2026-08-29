package com.saga.be.mail;

public class DisabledEmailSender implements EmailSender {

	@Override
	public boolean isEnabled() {
		return false;
	}

	@Override
	public void send(EmailMessage message) {
		throw new EmailSendException(EmailFailureCodes.MAIL_DISABLED, "Mail sender is disabled.");
	}
}
