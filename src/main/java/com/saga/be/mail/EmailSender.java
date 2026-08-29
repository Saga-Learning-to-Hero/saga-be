package com.saga.be.mail;

public interface EmailSender {

	boolean isEnabled();

	void send(EmailMessage message);
}
