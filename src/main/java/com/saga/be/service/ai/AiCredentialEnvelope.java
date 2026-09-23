package com.saga.be.service.ai;

/** Wire shape sent from saga-be to saga-ai for a COURSE-sourced request. Deliberately minimal
 * (section VI): version, algorithm, nonce, ciphertext -- never the DB at-rest key/nonce, always a
 * fresh nonce re-encrypted under the separate transport key. */
public record AiCredentialEnvelope(int version, String algorithm, String nonce, String ciphertext) {}
