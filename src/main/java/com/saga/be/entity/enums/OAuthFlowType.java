package com.saga.be.entity.enums;

public enum OAuthFlowType {
	GITHUB_USER_LINK,
	GITHUB_TEAM_INSTALL_VERIFY,
	/** Existing-installation reconnect via user OAuth + /user/installations (no /installations/new). */
	GITHUB_TEAM_RECONNECT,
	JIRA_USER_LINK,
	JIRA_TEAM_CONNECT
}
