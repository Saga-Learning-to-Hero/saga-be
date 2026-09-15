package com.saga.be.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.enums.PushPlatform;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FcmPayloadTest {

	@Test
	void dataContainsIdsAndOmitsUnsafeActionUrl() {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		Map<String, String> data = FcmPayload.data(new PushNotification(
				"token", "Title", "Body", id, "TEAM", "javascript:alert(1)", PushPlatform.WEB));
		assertEquals(id.toString(), data.get("notificationId"));
		assertEquals("TEAM", data.get("notificationType"));
		assertFalse(data.containsKey("actionUrl"));
		assertNull(FcmPayload.navigationTarget(" data:text/html,x "));
		assertEquals("/inbox", FcmPayload.navigationTarget(" /inbox "));
		assertTrue(FcmPayload.data(new PushNotification(
						"token", "Title", "Body", id, "SYSTEM", "/projects/1", PushPlatform.WEB))
				.containsKey("actionUrl"));
	}
}
