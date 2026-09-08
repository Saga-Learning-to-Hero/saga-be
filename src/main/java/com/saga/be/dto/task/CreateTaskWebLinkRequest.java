package com.saga.be.dto.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskWebLinkRequest(
		@NotBlank @Size(max = 2048) String url, @Size(max = 255) String title) {}
