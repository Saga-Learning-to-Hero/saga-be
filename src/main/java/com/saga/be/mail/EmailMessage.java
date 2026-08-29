package com.saga.be.mail;

public record EmailMessage(String to, String subject, String textBody, String htmlBody) {}
