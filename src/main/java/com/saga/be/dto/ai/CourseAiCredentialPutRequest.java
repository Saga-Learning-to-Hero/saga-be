package com.saga.be.dto.ai;

/** The apiKey is decrypted-in-transit by TLS and never persisted in plaintext; see {@code
 * CourseAiCredentialService}. This DTO itself must never be logged. */
public record CourseAiCredentialPutRequest(String provider, String apiKey) {}
