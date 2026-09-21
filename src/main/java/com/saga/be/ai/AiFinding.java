package com.saga.be.ai;

import java.util.List;

public record AiFinding(String code, String message, List<AiEvidenceReference> evidence) {}
