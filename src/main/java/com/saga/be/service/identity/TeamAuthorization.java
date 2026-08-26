package com.saga.be.service.identity;

import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public final class TeamAuthorization {

	public record Membership(UUID teamId, UUID projectId, UUID courseId, RoleInTeam role, UUID userId) {}

	private TeamAuthorization() {}

	public static void requireLeaderOrAdmin(Membership membership, AccountRole accountRole) {
		if (accountRole == AccountRole.ADMIN) {
			return;
		}
		if (membership == null) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "Not a member of this team.");
		}
		if (membership.role() != RoleInTeam.LEADER) {
			throw new IntegrationException(
					IntegrationErrorCode.NOT_TEAM_LEADER,
					HttpStatus.FORBIDDEN,
					"Only the Team Leader can change team integrations.");
		}
	}

	public static void requireMember(Membership membership) {
		if (membership == null) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "Not a member of this team.");
		}
	}
}
