package com.saga.be.dto.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record DevEmailTestRequest(@NotBlank @Email String to, String templateKey) {}
