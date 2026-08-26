package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.exception.IntegrationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamAuthorizationTest {

	@Test
	void leaderCanMutateTeamIntegration() {
		TeamAuthorization.Membership leader = new TeamAuthorization.Membership(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), RoleInTeam.LEADER, UUID.randomUUID());
		assertDoesNotThrow(() -> TeamAuthorization.requireLeaderOrAdmin(leader, AccountRole.STUDENT));
	}

	@Test
	void memberCannotConnectTeamResources() {
		TeamAuthorization.Membership member = new TeamAuthorization.Membership(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), RoleInTeam.MEMBER, UUID.randomUUID());
		assertThrows(IntegrationException.class, () -> TeamAuthorization.requireLeaderOrAdmin(member, AccountRole.STUDENT));
	}

	@Test
	void adminMayBypass() {
		assertDoesNotThrow(() -> TeamAuthorization.requireLeaderOrAdmin(null, AccountRole.ADMIN));
	}

	@Test
	void outsiderCannotStartWorkSession() {
		assertThrows(IntegrationException.class, () -> TeamAuthorization.requireMember(null));
	}
}
