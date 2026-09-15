package com.saga.be.graph;

import com.saga.be.dto.graph.CytoscapeGraphResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public record GraphRead(CytoscapeGraphResponse body, long revision, String viewKey) {

	public GraphRead(CytoscapeGraphResponse body, long revision) {
		this(body, revision, "");
	}

	public String etag(UUID projectId) {
		return ProjectGraphProjector.etag(projectId, revision, viewKey);
	}

	static String viewKeyHash(String viewKey) {
		if (viewKey == null || viewKey.isBlank()) {
			return "";
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(viewKey.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (Exception ex) {
			return Integer.toHexString(viewKey.hashCode());
		}
	}
}
