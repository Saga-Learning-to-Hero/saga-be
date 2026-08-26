package com.saga.be.service.attribution;

import com.saga.be.entity.enums.WarningSeverity;
import java.util.EnumSet;
import java.util.Set;

public final class AttributionWarningRouter {

	private AttributionWarningRouter() {}

	public record Delivery(boolean persistWarning, boolean inAppNotification, boolean emailLecturer) {}

	public static Delivery forSeverity(WarningSeverity severity) {
		if (severity == WarningSeverity.CRITICAL || severity == WarningSeverity.HIGH) {
			return new Delivery(true, true, true);
		}
		if (severity == WarningSeverity.MEDIUM || severity == WarningSeverity.WARNING) {
			return new Delivery(true, true, false);
		}
		return new Delivery(true, false, false);
	}

	public static Set<WarningSeverity> emailSeverities() {
		return EnumSet.of(WarningSeverity.HIGH, WarningSeverity.CRITICAL);
	}
}
