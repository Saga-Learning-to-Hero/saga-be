#!/usr/bin/env python3
"""Generate SAGA V2 JPA entities matching V1__initial_schema.sql."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/java/com/saga/be/entity"

PACKAGES = {
    "UserAccount": "account",
    "StudentProfile": "account",
    "LecturerProfile": "account",
    "StudentCourseInvitation": "account",
    "Subject": "academic",
    "AcademicClass": "academic",
    "Semester": "academic",
    "ActiveSemesterSetting": "academic",
    "Course": "academic",
    "CourseEnrollment": "academic",
    "ProjectType": "project",
    "Project": "project",
    "Team": "project",
    "TeamMember": "project",
    "JiraIntegration": "jira",
    "Sprint": "jira",
    "Task": "jira",
    "TaskAttachment": "jira",
    "JiraWriteOperation": "jira",
    "GithubInstallation": "github",
    "GitRepo": "github",
    "GitIssue": "github",
    "PullRequest": "github",
    "GitCommit": "github",
    "PrReview": "github",
    "Comment": "github",
    "TaskGitIssueLink": "traceability",
    "GitIssueCommitLink": "traceability",
    "GitIssuePullRequestLink": "traceability",
    "CommitReviewIntent": "github",
    "CommitReviewResult": "github",
    "IdentityMap": "integration",
    "IdentityMappingHistory": "integration",
    "WebhookReceipt": "integration",
    "SyncJobLog": "integration",
    "PeerReview": "assessment",
    "PeerReviewDetail": "assessment",
    "RubricTemplate": "assessment",
    "ProjectGroupWeightConfig": "assessment",
    "ContributionOverride": "assessment",
    "AssessmentRun": "assessment",
    "AssessmentResult": "assessment",
    "UserNotification": "notification",
    "NotificationBroadcast": "notification",
    "NotificationDelivery": "notification",
    "FirebaseInstallation": "notification",
    "EmailOutbox": "notification",
    "BusinessWarning": "warning",
    "AiAgentDelegationContext": "ai",
    "AiAgentConversationScope": "ai",
    "GraphProcessingRun": "graph",
    "OutboxEvent": "infra",
}

ENUMS = {
    "AccountRole": "com.saga.be.entity.enums.AccountRole",
    "AccountStatus": "com.saga.be.entity.enums.AccountStatus",
    "EnrollmentStatus": "com.saga.be.entity.enums.EnrollmentStatus",
    "RoleInTeam": "com.saga.be.entity.enums.RoleInTeam",
    "ContributionConfigMode": "com.saga.be.entity.enums.ContributionConfigMode",
    "IntegrationStatus": "com.saga.be.entity.enums.IntegrationStatus",
    "IntegrationProvider": "com.saga.be.entity.enums.IntegrationProvider",
    "IdentityMappingStatus": "com.saga.be.entity.enums.IdentityMappingStatus",
    "IdentityMappingAction": "com.saga.be.entity.enums.IdentityMappingAction",
    "BoardType": "com.saga.be.entity.enums.BoardType",
    "TaskType": "com.saga.be.entity.enums.TaskType",
    "TaskStatus": "com.saga.be.entity.enums.TaskStatus",
    "Priority": "com.saga.be.entity.enums.Priority",
    "IssueState": "com.saga.be.entity.enums.IssueState",
    "PullRequestStatus": "com.saga.be.entity.enums.PullRequestStatus",
    "PrReviewStatus": "com.saga.be.entity.enums.PrReviewStatus",
    "TraceabilityRelationType": "com.saga.be.entity.enums.TraceabilityRelationType",
    "TargetType": "com.saga.be.entity.enums.TargetType",
    "GitHubInstallationStatus": "com.saga.be.entity.enums.GitHubInstallationStatus",
    "JiraWriteOperationType": "com.saga.be.entity.enums.JiraWriteOperationType",
    "JiraWriteOperationStatus": "com.saga.be.entity.enums.JiraWriteOperationStatus",
    "WebhookReceiptStatus": "com.saga.be.entity.enums.WebhookReceiptStatus",
    "SyncJobType": "com.saga.be.entity.enums.SyncJobType",
    "SyncJobStatus": "com.saga.be.entity.enums.SyncJobStatus",
    "StudentInvitationType": "com.saga.be.entity.enums.StudentInvitationType",
    "StudentInvitationStatus": "com.saga.be.entity.enums.StudentInvitationStatus",
    "CommitReviewIntentStatus": "com.saga.be.entity.enums.CommitReviewIntentStatus",
    "CommitReviewMode": "com.saga.be.entity.enums.CommitReviewMode",
    "CommitReviewPriority": "com.saga.be.entity.enums.CommitReviewPriority",
    "NotificationType": "com.saga.be.entity.enums.NotificationType",
    "BroadcastAudience": "com.saga.be.entity.enums.BroadcastAudience",
    "BroadcastStatus": "com.saga.be.entity.enums.BroadcastStatus",
    "DeliveryStatus": "com.saga.be.entity.enums.DeliveryStatus",
    "EmailDeliveryStatus": "com.saga.be.entity.enums.EmailDeliveryStatus",
    "WarningCategory": "com.saga.be.entity.enums.WarningCategory",
    "WarningSeverity": "com.saga.be.entity.enums.WarningSeverity",
    "SprintProgressMode": "com.saga.be.entity.enums.SprintProgressMode",
    "GraphProcessingKind": "com.saga.be.entity.enums.GraphProcessingKind",
    "OutboxStatus": "com.saga.be.entity.enums.OutboxStatus",
    "AssessmentRunType": "com.saga.be.entity.enums.AssessmentRunType",
    "AssessmentRunStatus": "com.saga.be.entity.enums.AssessmentRunStatus",
}


def fqn(cls: str) -> str:
    return f"com.saga.be.entity.{PACKAGES[cls]}.{cls}"


def render_field(f: dict) -> tuple[str, set[str]]:
    imports: set[str] = set()
    kind = f["kind"]
    lines: list[str] = []
    if kind == "manytoone":
        imports.add("jakarta.persistence.FetchType")
        imports.add("jakarta.persistence.JoinColumn")
        imports.add("jakarta.persistence.ManyToOne")
        target = f["type"]
        imports.add(fqn(target))
        optional = f.get("optional", True)
        nullable = "true" if optional else "false"
        opt = "true" if optional else "false"
        lines.append(f"\t@ManyToOne(fetch = FetchType.LAZY, optional = {opt})")
        lines.append(f'\t@JoinColumn(name = "{f["col"]}", nullable = {nullable})')
        lines.append(f"\tprivate {target} {f['name']};")
    elif kind == "enum":
        imports.add("jakarta.persistence.EnumType")
        imports.add("jakarta.persistence.Enumerated")
        imports.add("jakarta.persistence.Column")
        imports.add(ENUMS[f["enum"]])
        nullable = f.get("nullable", True)
        length = f.get("length", 32)
        nn = "" if nullable else ", nullable = false"
        lines.append("\t@Enumerated(EnumType.STRING)")
        lines.append(f'\t@Column(name = "{f["col"]}", length = {length}{nn})')
        lines.append(f"\tprivate {f['enum']} {f['name']};")
    elif kind == "string":
        imports.add("jakarta.persistence.Column")
        nullable = f.get("nullable", True)
        length = f.get("length")
        cdef = f.get("columnDefinition")
        parts = [f'name = "{f["col"]}"']
        if length:
            parts.append(f"length = {length}")
        if cdef:
            parts.append(f'columnDefinition = "{cdef}"')
        if not nullable:
            parts.append("nullable = false")
        lines.append(f"\t@Column({', '.join(parts)})")
        lines.append(f"\tprivate String {f['name']};")
    elif kind == "bool":
        imports.add("jakarta.persistence.Column")
        nn = "" if f.get("nullable", True) else ", nullable = false"
        lines.append(f'\t@Column(name = "{f["col"]}"{nn})')
        lines.append(f"\tprivate Boolean {f['name']};")
    elif kind == "int":
        imports.add("jakarta.persistence.Column")
        nn = "" if f.get("nullable", True) else ", nullable = false"
        lines.append(f'\t@Column(name = "{f["col"]}"{nn})')
        lines.append(f"\tprivate Integer {f['name']};")
    elif kind == "long":
        imports.add("jakarta.persistence.Column")
        nn = "" if f.get("nullable", True) else ", nullable = false"
        lines.append(f'\t@Column(name = "{f["col"]}"{nn})')
        lines.append(f"\tprivate Long {f['name']};")
    elif kind == "double":
        imports.add("jakarta.persistence.Column")
        nn = "" if f.get("nullable", True) else ", nullable = false"
        lines.append(f'\t@Column(name = "{f["col"]}"{nn})')
        lines.append(f"\tprivate Double {f['name']};")
    elif kind == "decimal":
        imports.add("jakarta.persistence.Column")
        imports.add("java.math.BigDecimal")
        nn = "" if f.get("nullable", True) else ", nullable = false"
        p = f.get("precision", 10)
        s = f.get("scale", 4)
        lines.append(f'\t@Column(name = "{f["col"]}", precision = {p}, scale = {s}{nn})')
        lines.append(f"\tprivate BigDecimal {f['name']};")
    elif kind == "dt":
        imports.add("jakarta.persistence.Column")
        imports.add("java.time.LocalDateTime")
        nn = "" if f.get("nullable", True) else ", nullable = false"
        lines.append(f'\t@Column(name = "{f["col"]}"{nn})')
        lines.append(f"\tprivate LocalDateTime {f['name']};")
    elif kind == "uuid":
        imports.add("jakarta.persistence.Column")
        imports.add("java.sql.Types")
        imports.add("java.util.UUID")
        imports.add("org.hibernate.annotations.JdbcTypeCode")
        nn = "" if f.get("nullable", True) else ", nullable = false"
        lines.append("\t@JdbcTypeCode(Types.CHAR)")
        lines.append(f'\t@Column(name = "{f["col"]}", columnDefinition = "char(36)"{nn})')
        lines.append(f"\tprivate UUID {f['name']};")
    elif kind == "json":
        imports.add("jakarta.persistence.Column")
        imports.add("org.hibernate.annotations.JdbcTypeCode")
        imports.add("org.hibernate.type.SqlTypes")
        nn = "" if f.get("nullable", True) else ", nullable = false"
        lines.append("\t@JdbcTypeCode(SqlTypes.JSON)")
        lines.append(f'\t@Column(name = "{f["col"]}", columnDefinition = "json"{nn})')
        lines.append(f"\tprivate String {f['name']};")
    elif kind == "version":
        imports.add("jakarta.persistence.Column")
        imports.add("jakarta.persistence.Version")
        lines.append("\t@Version")
        lines.append(f'\t@Column(name = "{f["col"]}", nullable = false)')
        lines.append(f"\tprivate Long {f['name']};")
    elif kind == "byte":
        imports.add("jakarta.persistence.Column")
        imports.add("jakarta.persistence.Id")
        lines.append("\t@Id")
        lines.append(f'\t@Column(name = "{f["col"]}", nullable = false)')
        lines.append(f"\tprivate Byte {f['name']};")
    else:
        raise ValueError(kind)
    return "\n".join(lines), imports


def render_entity(spec: dict) -> str:
    pkg = PACKAGES[spec["name"]]
    imports = {
        "jakarta.persistence.Entity",
        "jakarta.persistence.Table",
        "lombok.Getter",
        "lombok.NoArgsConstructor",
        "lombok.Setter",
    }
    if spec.get("base", True):
        imports.add("com.saga.be.entity.BaseEntity")
    if spec.get("unique"):
        imports.add("jakarta.persistence.UniqueConstraint")
    if spec.get("indexes"):
        imports.add("jakarta.persistence.Index")
    field_blocks = []
    for f in spec["fields"]:
        block, extra = render_field(f)
        field_blocks.append(block)
        imports |= extra
    if spec.get("custom_id"):
        imports |= {
            "jakarta.persistence.Column",
            "jakarta.persistence.GeneratedValue",
            "jakarta.persistence.GenerationType",
            "jakarta.persistence.Id",
            "java.sql.Types",
            "java.util.UUID",
            "org.hibernate.annotations.JdbcTypeCode",
        }

    table_args = [f'name = "{spec["table"]}"']
    if spec.get("unique"):
        uqs = []
        for u in spec["unique"]:
            cols = ", ".join(f'"{c}"' for c in u["cols"])
            uqs.append(f'\t\t\t@UniqueConstraint(name = "{u["name"]}", columnNames = {{{cols}}})')
        table_args.append("uniqueConstraints = {\n" + ",\n".join(uqs) + "\n\t}")
    if spec.get("indexes"):
        ixs = []
        for i in spec["indexes"]:
            cols = ", ".join(f'"{c}"' for c in i["cols"])
            ixs.append(f'\t\t\t@Index(name = "{i["name"]}", columnList = "{", ".join(i["cols"])}")')
        table_args.append("indexes = {\n" + ",\n".join(ixs) + "\n\t}")

    lines = [
        f"package com.saga.be.entity.{pkg};",
        "",
    ]
    for imp in sorted(imports):
        lines.append(f"import {imp};")
    lines += [
        "",
        "@Getter",
        "@Setter",
        "@NoArgsConstructor",
        "@Entity",
        f"@Table({', '.join(table_args) if len(table_args) == 1 else chr(10) + chr(9) + (',' + chr(10) + chr(9)).join(table_args) + chr(10)})",
    ]
    # Fix Table annotation formatting
    if spec.get("unique") or spec.get("indexes"):
        table_inner = f'name = "{spec["table"]}"'
        extras = []
        if spec.get("unique"):
            uqs = []
            for u in spec["unique"]:
                cols = ", ".join(f'"{c}"' for c in u["cols"])
                uqs.append(f'\t\t@UniqueConstraint(name = "{u["name"]}", columnNames = {{{cols}}})')
            extras.append("\tuniqueConstraints = {\n" + ",\n".join(uqs) + "\n\t}")
        if spec.get("indexes"):
            ixs = []
            for i in spec["indexes"]:
                col_list = ", ".join(i["cols"])
                ixs.append(f'\t\t@Index(name = "{i["name"]}", columnList = "{col_list}")')
            extras.append("\tindexes = {\n" + ",\n".join(ixs) + "\n\t}")
        lines[-1] = "@Table(\n\t" + table_inner + ",\n" + ",\n".join(extras) + "\n)"
    else:
        lines[-1] = f'@Table(name = "{spec["table"]}")'

    parent = " extends BaseEntity" if spec.get("base", True) else ""
    lines.append(f"public class {spec['name']}{parent} {{")
    if spec.get("custom_id"):
        lines.append("")
        lines.append("\t@Id")
        lines.append("\t@GeneratedValue(strategy = GenerationType.UUID)")
        lines.append("\t@JdbcTypeCode(Types.CHAR)")
        lines.append('\t@Column(name = "id", columnDefinition = "char(36)", updatable = false, nullable = false)')
        lines.append("\tprivate UUID id;")
    for block in field_blocks:
        lines.append("")
        lines.append(block)
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


ENTITIES = [
    {
        "name": "UserAccount",
        "table": "user_account",
        "unique": [{"name": "uk_user_account_email", "cols": ["email"]}],
        "indexes": [{"name": "ix_user_account_role_status", "cols": ["account_role", "account_status"]}],
        "fields": [
            {"kind": "string", "name": "email", "col": "email", "length": 255, "nullable": False},
            {"kind": "string", "name": "fullName", "col": "full_name", "length": 255},
            {"kind": "string", "name": "avatarUrl", "col": "avatar_url", "length": 500},
            {"kind": "enum", "name": "accountRole", "col": "account_role", "enum": "AccountRole", "nullable": False},
            {"kind": "enum", "name": "accountStatus", "col": "account_status", "enum": "AccountStatus", "nullable": False},
        ],
    },
    {
        "name": "StudentProfile",
        "table": "student_profile",
        "unique": [
            {"name": "uk_student_profile_user", "cols": ["user_account_id"]},
            {"name": "uk_student_profile_code", "cols": ["student_code"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "userAccount", "col": "user_account_id", "type": "UserAccount", "optional": False},
            {"kind": "string", "name": "studentCode", "col": "student_code", "length": 64},
            {"kind": "manytoone", "name": "approvedBy", "col": "approved_by_user_id", "type": "UserAccount"},
            {"kind": "dt", "name": "approvedAt", "col": "approved_at"},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "LecturerProfile",
        "table": "lecturer_profile",
        "unique": [{"name": "uk_lecturer_profile_user", "cols": ["user_account_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "userAccount", "col": "user_account_id", "type": "UserAccount", "optional": False},
        ],
    },
    {
        "name": "Subject",
        "table": "subject",
        "unique": [{"name": "uk_subject_code", "cols": ["subject_code"]}],
        "fields": [
            {"kind": "string", "name": "subjectCode", "col": "subject_code", "length": 64, "nullable": False},
            {"kind": "string", "name": "name", "col": "name", "length": 255, "nullable": False},
            {"kind": "dt", "name": "deletedAt", "col": "deleted_at"},
        ],
    },
    {
        "name": "AcademicClass",
        "table": "academic_class",
        "unique": [{"name": "uk_academic_class_code", "cols": ["class_code"]}],
        "fields": [
            {"kind": "string", "name": "classCode", "col": "class_code", "length": 64, "nullable": False},
            {"kind": "string", "name": "name", "col": "name", "length": 255, "nullable": False},
            {"kind": "dt", "name": "deletedAt", "col": "deleted_at"},
        ],
    },
    {
        "name": "Semester",
        "table": "semester",
        "unique": [{"name": "uk_semester_code", "cols": ["code"]}],
        "fields": [
            {"kind": "string", "name": "code", "col": "code", "length": 64, "nullable": False},
            {"kind": "string", "name": "name", "col": "name", "length": 255, "nullable": False},
            {"kind": "dt", "name": "startDate", "col": "start_date"},
            {"kind": "dt", "name": "endDate", "col": "end_date"},
            {"kind": "dt", "name": "deletedAt", "col": "deleted_at"},
        ],
    },
    {
        "name": "ActiveSemesterSetting",
        "table": "active_semester_setting",
        "base": False,
        "fields": [
            {"kind": "byte", "name": "singletonId", "col": "singleton_id"},
            {"kind": "manytoone", "name": "semester", "col": "semester_id", "type": "Semester"},
            {"kind": "dt", "name": "updatedAt", "col": "updated_at", "nullable": False},
            {"kind": "manytoone", "name": "updatedBy", "col": "updated_by_user_id", "type": "UserAccount"},
        ],
    },
    {
        "name": "Course",
        "table": "course",
        "indexes": [
            {"name": "ix_course_semester_instructor", "cols": ["semester_id", "instructor_id"]},
            {"name": "ix_course_subject", "cols": ["subject_id"]},
            {"name": "ix_course_class", "cols": ["academic_class_id"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "subject", "col": "subject_id", "type": "Subject", "optional": False},
            {"kind": "manytoone", "name": "academicClass", "col": "academic_class_id", "type": "AcademicClass", "optional": False},
            {"kind": "manytoone", "name": "semester", "col": "semester_id", "type": "Semester", "optional": False},
            {"kind": "manytoone", "name": "instructor", "col": "instructor_id", "type": "LecturerProfile"},
            {"kind": "string", "name": "courseCode", "col": "course_code", "length": 64},
            {"kind": "string", "name": "name", "col": "name", "length": 255, "nullable": False},
            {"kind": "double", "name": "codeContributionWeight", "col": "code_contribution_weight", "nullable": False},
            {"kind": "double", "name": "testContributionWeight", "col": "test_contribution_weight", "nullable": False},
            {"kind": "double", "name": "documentContributionWeight", "col": "document_contribution_weight", "nullable": False},
            {"kind": "double", "name": "researchContributionWeight", "col": "research_contribution_weight", "nullable": False},
            {"kind": "enum", "name": "contributionConfigMode", "col": "contribution_config_mode", "enum": "ContributionConfigMode", "nullable": False},
            {"kind": "dt", "name": "deletedAt", "col": "deleted_at"},
        ],
    },
    {
        "name": "StudentCourseInvitation",
        "table": "student_course_invitation",
        "unique": [{"name": "uk_invitation_student_course_type", "cols": ["student_profile_id", "course_id", "invitation_type"]}],
        "indexes": [{"name": "ix_invitation_status", "cols": ["invitation_status"]}],
        "fields": [
            {"kind": "manytoone", "name": "studentProfile", "col": "student_profile_id", "type": "StudentProfile", "optional": False},
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course", "optional": False},
            {"kind": "enum", "name": "invitationType", "col": "invitation_type", "enum": "StudentInvitationType", "nullable": False},
            {"kind": "enum", "name": "invitationStatus", "col": "invitation_status", "enum": "StudentInvitationStatus", "nullable": False},
            {"kind": "int", "name": "attemptCount", "col": "attempt_count", "nullable": False},
            {"kind": "dt", "name": "lastAttemptAt", "col": "last_attempt_at"},
            {"kind": "dt", "name": "processingStartedAt", "col": "processing_started_at"},
            {"kind": "dt", "name": "sentAt", "col": "sent_at"},
            {"kind": "string", "name": "failureCode", "col": "failure_code", "length": 64},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "CourseEnrollment",
        "table": "course_enrollment",
        "unique": [
            {"name": "uk_enrollment_student_course", "cols": ["student_profile_id", "course_id"]},
            {"name": "uk_enrollment_id_course", "cols": ["id", "course_id"]},
        ],
        "indexes": [{"name": "ix_enrollment_course", "cols": ["course_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "studentProfile", "col": "student_profile_id", "type": "StudentProfile", "optional": False},
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course", "optional": False},
            {"kind": "enum", "name": "enrollmentStatus", "col": "enrollment_status", "enum": "EnrollmentStatus", "nullable": False},
            {"kind": "dt", "name": "enrolledAt", "col": "enrolled_at", "nullable": False},
        ],
    },
    {
        "name": "ProjectType",
        "table": "project_type",
        "unique": [{"name": "uk_project_type_code", "cols": ["code"]}],
        "fields": [
            {"kind": "string", "name": "code", "col": "code", "length": 64, "nullable": False},
            {"kind": "string", "name": "name", "col": "name", "length": 255, "nullable": False},
            {"kind": "string", "name": "description", "col": "description", "length": 1000},
            {"kind": "string", "name": "criteriaConfig", "col": "criteria_config", "columnDefinition": "TEXT"},
        ],
    },
    {
        "name": "Project",
        "table": "project",
        "indexes": [{"name": "ix_project_course", "cols": ["course_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course", "optional": False},
            {"kind": "manytoone", "name": "projectType", "col": "project_type_id", "type": "ProjectType"},
            {"kind": "string", "name": "name", "col": "name", "length": 255, "nullable": False},
            {"kind": "string", "name": "description", "col": "description", "columnDefinition": "MEDIUMTEXT"},
            {"kind": "string", "name": "repositoryUrl", "col": "repository_url", "length": 500},
            {"kind": "manytoone", "name": "createdBy", "col": "created_by_user_id", "type": "UserAccount"},
        ],
    },
    {
        "name": "Team",
        "table": "team",
        "unique": [
            {"name": "uk_team_project", "cols": ["project_id"]},
            {"name": "uk_team_id_course", "cols": ["id", "course_id"]},
        ],
        "indexes": [{"name": "ix_team_course", "cols": ["course_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course", "optional": False},
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project", "optional": False},
            {"kind": "string", "name": "name", "col": "name", "length": 255, "nullable": False},
        ],
    },
    {
        "name": "TeamMember",
        "table": "team_member",
        "unique": [{"name": "uk_team_member_enrollment", "cols": ["team_id", "course_enrollment_id"]}],
        "indexes": [{"name": "ix_team_member_enrollment", "cols": ["course_enrollment_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "team", "col": "team_id", "type": "Team", "optional": False},
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course", "optional": False},
            {"kind": "manytoone", "name": "courseEnrollment", "col": "course_enrollment_id", "type": "CourseEnrollment", "optional": False},
            {"kind": "enum", "name": "roleInTeam", "col": "role_in_team", "enum": "RoleInTeam", "nullable": False},
        ],
    },
    {
        "name": "JiraIntegration",
        "table": "jira_integration",
        "unique": [
            {"name": "uk_jira_integration_project", "cols": ["project_id"]},
            {"name": "uk_jira_cloud_project", "cols": ["cloud_id", "jira_project_id"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project", "optional": False},
            {"kind": "string", "name": "name", "col": "name", "length": 255},
            {"kind": "enum", "name": "boardType", "col": "board_type", "enum": "BoardType"},
            {"kind": "string", "name": "jiraBoardId", "col": "jira_board_id", "length": 64},
            {"kind": "string", "name": "cloudId", "col": "cloud_id", "length": 128},
            {"kind": "string", "name": "siteUrl", "col": "site_url", "length": 500},
            {"kind": "string", "name": "jiraProjectId", "col": "jira_project_id", "length": 64},
            {"kind": "string", "name": "projectKey", "col": "project_key", "length": 64},
            {"kind": "string", "name": "encryptedAccessToken", "col": "encrypted_access_token", "columnDefinition": "TEXT"},
            {"kind": "string", "name": "encryptedRefreshToken", "col": "encrypted_refresh_token", "columnDefinition": "TEXT"},
            {"kind": "dt", "name": "tokenExpiresAt", "col": "token_expires_at"},
            {"kind": "string", "name": "grantedScopes", "col": "granted_scopes", "columnDefinition": "TEXT"},
            {"kind": "enum", "name": "connectionStatus", "col": "connection_status", "enum": "IntegrationStatus", "nullable": False},
            {"kind": "manytoone", "name": "connectedBy", "col": "connected_by_user_id", "type": "UserAccount"},
            {"kind": "string", "name": "webhookId", "col": "webhook_id", "length": 128},
            {"kind": "dt", "name": "webhookExpiresAt", "col": "webhook_expires_at"},
            {"kind": "string", "name": "webhookSecretHash", "col": "webhook_secret_hash", "length": 64},
            {"kind": "dt", "name": "syncCursor", "col": "sync_cursor"},
            {"kind": "int", "name": "consecutiveFailures", "col": "consecutive_failures", "nullable": False},
            {"kind": "dt", "name": "lastSyncedAt", "col": "last_synced_at"},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "Sprint",
        "table": "sprint",
        "unique": [{"name": "uk_sprint_external", "cols": ["jira_integration_id", "external_sprint_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "jiraIntegration", "col": "jira_integration_id", "type": "JiraIntegration", "optional": False},
            {"kind": "string", "name": "name", "col": "name", "length": 255},
            {"kind": "string", "name": "externalSprintId", "col": "external_sprint_id", "length": 128},
            {"kind": "dt", "name": "startDate", "col": "start_date"},
            {"kind": "dt", "name": "endDate", "col": "end_date"},
            {"kind": "string", "name": "goal", "col": "goal", "length": 1000},
            {"kind": "string", "name": "state", "col": "state", "length": 64},
            {"kind": "dt", "name": "completeDate", "col": "complete_date"},
            {"kind": "dt", "name": "deletedAt", "col": "deleted_at"},
        ],
    },
    {
        "name": "Task",
        "table": "task",
        "unique": [{"name": "uk_task_project_external_id", "cols": ["project_id", "external_id"]}],
        "indexes": [
            {"name": "ix_task_project_sprint", "cols": ["project_id", "sprint_id"]},
            {"name": "ix_task_assignee", "cols": ["assignee_student_id"]},
            {"name": "ix_task_due_date", "cols": ["due_date"]},
            {"name": "ix_task_external_key", "cols": ["external_key"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project", "optional": False},
            {"kind": "manytoone", "name": "sprint", "col": "sprint_id", "type": "Sprint"},
            {"kind": "manytoone", "name": "assigneeStudent", "col": "assignee_student_id", "type": "StudentProfile"},
            {"kind": "manytoone", "name": "reporterStudent", "col": "reporter_student_id", "type": "StudentProfile"},
            {"kind": "string", "name": "assigneeExternalId", "col": "assignee_external_id", "length": 128},
            {"kind": "string", "name": "reporterExternalId", "col": "reporter_external_id", "length": 128},
            {"kind": "manytoone", "name": "blocksTask", "col": "blocks_task_id", "type": "Task"},
            {"kind": "string", "name": "externalKey", "col": "external_key", "length": 64},
            {"kind": "string", "name": "externalId", "col": "external_id", "length": 64},
            {"kind": "string", "name": "title", "col": "title", "length": 500},
            {"kind": "enum", "name": "taskType", "col": "task_type", "enum": "TaskType"},
            {"kind": "enum", "name": "status", "col": "status", "enum": "TaskStatus"},
            {"kind": "enum", "name": "priority", "col": "priority", "enum": "Priority"},
            {"kind": "int", "name": "storyPoint", "col": "story_point"},
            {"kind": "dt", "name": "dueDate", "col": "due_date"},
            {"kind": "dt", "name": "externalUpdatedAt", "col": "external_updated_at"},
            {"kind": "dt", "name": "resolvedAt", "col": "resolved_at"},
            {"kind": "string", "name": "resolution", "col": "resolution", "length": 128},
            {"kind": "string", "name": "description", "col": "description", "columnDefinition": "TEXT"},
            {"kind": "string", "name": "labelsJson", "col": "labels_json", "columnDefinition": "TEXT"},
            {"kind": "string", "name": "componentsJson", "col": "components_json", "columnDefinition": "TEXT"},
            {"kind": "dt", "name": "deletedAt", "col": "deleted_at"},
        ],
    },
    {
        "name": "TaskAttachment",
        "table": "task_attachment",
        "unique": [{"name": "uk_task_attachment_external", "cols": ["task_id", "external_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "task", "col": "task_id", "type": "Task", "optional": False},
            {"kind": "string", "name": "externalId", "col": "external_id", "length": 64, "nullable": False},
            {"kind": "string", "name": "filename", "col": "filename", "length": 512},
            {"kind": "string", "name": "mimeType", "col": "mime_type", "length": 255},
            {"kind": "long", "name": "sizeBytes", "col": "size_bytes"},
            {"kind": "string", "name": "authorExternalId", "col": "author_external_id", "length": 128},
        ],
    },
    {
        "name": "JiraWriteOperation",
        "table": "jira_write_operation",
        "unique": [{"name": "uk_jira_write_project_key", "cols": ["project_id", "idempotency_key"]}],
        "fields": [
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project", "optional": False},
            {"kind": "manytoone", "name": "actorUser", "col": "actor_user_id", "type": "UserAccount", "optional": False},
            {"kind": "enum", "name": "operationType", "col": "operation_type", "enum": "JiraWriteOperationType", "nullable": False},
            {"kind": "string", "name": "idempotencyKey", "col": "idempotency_key", "length": 128, "nullable": False},
            {"kind": "string", "name": "requestFingerprint", "col": "request_fingerprint", "length": 64, "nullable": False},
            {"kind": "string", "name": "remoteResourceId", "col": "remote_resource_id", "length": 128},
            {"kind": "string", "name": "remoteResourceKey", "col": "remote_resource_key", "length": 128},
            {"kind": "enum", "name": "status", "col": "status", "enum": "JiraWriteOperationStatus", "nullable": False},
            {"kind": "string", "name": "safeErrorCode", "col": "safe_error_code", "length": 64},
            {"kind": "dt", "name": "completedAt", "col": "completed_at"},
        ],
    },
    {
        "name": "GithubInstallation",
        "table": "github_installation",
        "unique": [{"name": "uk_github_installation_id", "cols": ["installation_id"]}],
        "fields": [
            {"kind": "long", "name": "installationId", "col": "installation_id", "nullable": False},
            {"kind": "manytoone", "name": "installedBy", "col": "installed_by_user_id", "type": "UserAccount"},
            {"kind": "string", "name": "accountLogin", "col": "account_login", "length": 255},
            {"kind": "string", "name": "accountType", "col": "account_type", "length": 64},
            {"kind": "enum", "name": "installationStatus", "col": "installation_status", "enum": "GitHubInstallationStatus", "nullable": False},
            {"kind": "dt", "name": "lastVerifiedAt", "col": "last_verified_at"},
            {"kind": "int", "name": "consecutiveFailures", "col": "consecutive_failures", "nullable": False},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "GitRepo",
        "table": "git_repo",
        "unique": [
            {"name": "uk_git_repo_provider_id", "cols": ["provider", "repository_id"]},
            {"name": "uk_git_repo_project_full_name", "cols": ["project_id", "full_name"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project", "optional": False},
            {"kind": "manytoone", "name": "installation", "col": "installation_id", "type": "GithubInstallation"},
            {"kind": "string", "name": "name", "col": "name", "length": 255},
            {"kind": "string", "name": "url", "col": "url", "length": 500},
            {"kind": "enum", "name": "provider", "col": "provider", "enum": "IntegrationProvider", "nullable": False},
            {"kind": "long", "name": "repositoryId", "col": "repository_id"},
            {"kind": "string", "name": "ownerLogin", "col": "owner_login", "length": 255},
            {"kind": "string", "name": "fullName", "col": "full_name", "length": 255},
            {"kind": "string", "name": "defaultBranch", "col": "default_branch", "length": 128},
            {"kind": "enum", "name": "connectionStatus", "col": "connection_status", "enum": "IntegrationStatus", "nullable": False},
            {"kind": "dt", "name": "syncCursor", "col": "sync_cursor"},
            {"kind": "int", "name": "consecutiveFailures", "col": "consecutive_failures", "nullable": False},
            {"kind": "dt", "name": "lastSyncedAt", "col": "last_synced_at"},
            {"kind": "dt", "name": "reviewCutoverAt", "col": "review_cutover_at"},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "GitIssue",
        "table": "git_issue",
        "unique": [
            {"name": "uk_git_issue_github_id", "cols": ["repo_id", "github_issue_id"]},
            {"name": "uk_git_issue_number", "cols": ["repo_id", "issue_number"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "repo", "col": "repo_id", "type": "GitRepo", "optional": False},
            {"kind": "manytoone", "name": "authorStudent", "col": "author_student_id", "type": "StudentProfile"},
            {"kind": "manytoone", "name": "assigneeStudent", "col": "assignee_student_id", "type": "StudentProfile"},
            {"kind": "string", "name": "authorExternalId", "col": "author_external_id", "length": 128},
            {"kind": "string", "name": "assigneeExternalId", "col": "assignee_external_id", "length": 128},
            {"kind": "int", "name": "issueNumber", "col": "issue_number"},
            {"kind": "long", "name": "githubIssueId", "col": "github_issue_id"},
            {"kind": "string", "name": "nodeId", "col": "node_id", "length": 128},
            {"kind": "string", "name": "title", "col": "title", "length": 500},
            {"kind": "enum", "name": "state", "col": "state", "enum": "IssueState"},
            {"kind": "dt", "name": "closedAt", "col": "closed_at"},
            {"kind": "dt", "name": "externalUpdatedAt", "col": "external_updated_at"},
        ],
    },
    {
        "name": "PullRequest",
        "table": "pull_request",
        "unique": [
            {"name": "uk_pr_github_id", "cols": ["repo_id", "github_pull_request_id"]},
            {"name": "uk_pr_number", "cols": ["repo_id", "pull_number"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "repo", "col": "repo_id", "type": "GitRepo", "optional": False},
            {"kind": "manytoone", "name": "authorStudent", "col": "author_student_id", "type": "StudentProfile"},
            {"kind": "string", "name": "authorExternalId", "col": "author_external_id", "length": 128},
            {"kind": "string", "name": "title", "col": "title", "length": 500},
            {"kind": "long", "name": "githubPullRequestId", "col": "github_pull_request_id"},
            {"kind": "string", "name": "nodeId", "col": "node_id", "length": 128},
            {"kind": "int", "name": "pullNumber", "col": "pull_number"},
            {"kind": "enum", "name": "status", "col": "status", "enum": "PullRequestStatus"},
            {"kind": "dt", "name": "mergedAt", "col": "merged_at"},
            {"kind": "int", "name": "reviewCount", "col": "review_count"},
            {"kind": "int", "name": "commentCount", "col": "comment_count"},
            {"kind": "dt", "name": "externalUpdatedAt", "col": "external_updated_at"},
        ],
    },
    {
        "name": "GitCommit",
        "table": "git_commit",
        "unique": [{"name": "uk_git_commit_repo_sha", "cols": ["repo_id", "sha_hash"]}],
        "indexes": [{"name": "ix_git_commit_sha", "cols": ["sha_hash"]}],
        "fields": [
            {"kind": "manytoone", "name": "repo", "col": "repo_id", "type": "GitRepo", "optional": False},
            {"kind": "manytoone", "name": "authorStudent", "col": "author_student_id", "type": "StudentProfile"},
            {"kind": "string", "name": "shaHash", "col": "sha_hash", "length": 64, "nullable": False},
            {"kind": "string", "name": "githubCommitId", "col": "github_commit_id", "length": 64},
            {"kind": "string", "name": "authorExternalId", "col": "author_external_id", "length": 128},
            {"kind": "string", "name": "message", "col": "message", "columnDefinition": "MEDIUMTEXT"},
            {"kind": "dt", "name": "committedAt", "col": "committed_at"},
            {"kind": "int", "name": "additions", "col": "additions"},
            {"kind": "int", "name": "deletions", "col": "deletions"},
            {"kind": "int", "name": "filesChanged", "col": "files_changed"},
            {"kind": "dt", "name": "externalUpdatedAt", "col": "external_updated_at"},
        ],
    },
    {
        "name": "PrReview",
        "table": "pr_review",
        "unique": [{"name": "uk_pr_review_github_id", "cols": ["pull_request_id", "github_review_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "pullRequest", "col": "pull_request_id", "type": "PullRequest", "optional": False},
            {"kind": "manytoone", "name": "reviewerStudent", "col": "reviewer_student_id", "type": "StudentProfile"},
            {"kind": "enum", "name": "status", "col": "status", "enum": "PrReviewStatus"},
            {"kind": "dt", "name": "reviewedAt", "col": "reviewed_at"},
            {"kind": "long", "name": "githubReviewId", "col": "github_review_id"},
            {"kind": "string", "name": "reviewerExternalId", "col": "reviewer_external_id", "length": 128},
            {"kind": "dt", "name": "externalUpdatedAt", "col": "external_updated_at"},
        ],
    },
    {
        "name": "Comment",
        "table": "comment",
        "indexes": [
            {"name": "ix_comment_issue", "cols": ["git_issue_id"]},
            {"name": "ix_comment_pr", "cols": ["pull_request_id"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "authorStudent", "col": "author_student_id", "type": "StudentProfile"},
            {"kind": "manytoone", "name": "gitIssue", "col": "git_issue_id", "type": "GitIssue"},
            {"kind": "manytoone", "name": "pullRequest", "col": "pull_request_id", "type": "PullRequest"},
            {"kind": "manytoone", "name": "parentComment", "col": "parent_comment_id", "type": "Comment"},
            {"kind": "string", "name": "body", "col": "body", "columnDefinition": "TEXT"},
            {"kind": "string", "name": "sourceSystem", "col": "source_system", "length": 32},
            {"kind": "string", "name": "externalCommentId", "col": "external_comment_id", "length": 128},
            {"kind": "string", "name": "authorExternalId", "col": "author_external_id", "length": 128},
            {"kind": "enum", "name": "targetType", "col": "target_type", "enum": "TargetType"},
            {"kind": "dt", "name": "externalUpdatedAt", "col": "external_updated_at"},
        ],
    },
    {
        "name": "TaskGitIssueLink",
        "table": "task_git_issue_link",
        "unique": [{"name": "uk_task_git_issue_link_pair", "cols": ["task_id", "git_issue_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "task", "col": "task_id", "type": "Task", "optional": False},
            {"kind": "manytoone", "name": "gitIssue", "col": "git_issue_id", "type": "GitIssue", "optional": False},
            {"kind": "enum", "name": "relationType", "col": "relation_type", "enum": "TraceabilityRelationType", "nullable": False},
        ],
    },
    {
        "name": "GitIssueCommitLink",
        "table": "git_issue_commit_link",
        "unique": [{"name": "uk_git_issue_commit_link_pair", "cols": ["git_issue_id", "git_commit_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "gitIssue", "col": "git_issue_id", "type": "GitIssue", "optional": False},
            {"kind": "manytoone", "name": "gitCommit", "col": "git_commit_id", "type": "GitCommit", "optional": False},
            {"kind": "enum", "name": "relationType", "col": "relation_type", "enum": "TraceabilityRelationType", "nullable": False},
        ],
    },
    {
        "name": "GitIssuePullRequestLink",
        "table": "git_issue_pull_request_link",
        "unique": [{"name": "uk_git_issue_pr_link_pair", "cols": ["git_issue_id", "pull_request_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "gitIssue", "col": "git_issue_id", "type": "GitIssue", "optional": False},
            {"kind": "manytoone", "name": "pullRequest", "col": "pull_request_id", "type": "PullRequest", "optional": False},
            {"kind": "enum", "name": "relationType", "col": "relation_type", "enum": "TraceabilityRelationType", "nullable": False},
        ],
    },
    {
        "name": "CommitReviewIntent",
        "table": "commit_review_intent",
        "unique": [{"name": "uk_commit_review_intent_repo_sha", "cols": ["git_repo_id", "sha_hash"]}],
        "fields": [
            {"kind": "manytoone", "name": "gitRepo", "col": "git_repo_id", "type": "GitRepo", "optional": False},
            {"kind": "manytoone", "name": "gitCommit", "col": "git_commit_id", "type": "GitCommit", "optional": False},
            {"kind": "string", "name": "shaHash", "col": "sha_hash", "length": 64, "nullable": False},
            {"kind": "enum", "name": "reviewMode", "col": "review_mode", "enum": "CommitReviewMode", "nullable": False},
            {"kind": "enum", "name": "priority", "col": "priority", "enum": "CommitReviewPriority", "length": 16, "nullable": False},
            {"kind": "int", "name": "priorityRank", "col": "priority_rank", "nullable": False},
            {"kind": "enum", "name": "intentStatus", "col": "intent_status", "enum": "CommitReviewIntentStatus", "nullable": False},
            {"kind": "uuid", "name": "aiJobId", "col": "ai_job_id"},
            {"kind": "string", "name": "reviewPolicyVersion", "col": "review_policy_version", "length": 64},
            {"kind": "string", "name": "lastJobStatus", "col": "last_job_status", "length": 32},
            {"kind": "dt", "name": "startedAt", "col": "started_at"},
            {"kind": "dt", "name": "completedAt", "col": "completed_at"},
            {"kind": "string", "name": "safeErrorCode", "col": "safe_error_code", "length": 64},
        ],
    },
    {
        "name": "CommitReviewResult",
        "table": "commit_review_result",
        "unique": [
            {"name": "uk_commit_review_result_intent", "cols": ["intent_id"]},
            {"name": "uk_commit_review_result_job", "cols": ["ai_job_id"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "intent", "col": "intent_id", "type": "CommitReviewIntent", "optional": False},
            {"kind": "uuid", "name": "aiJobId", "col": "ai_job_id", "nullable": False},
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project", "optional": False},
            {"kind": "manytoone", "name": "gitRepo", "col": "git_repo_id", "type": "GitRepo", "optional": False},
            {"kind": "manytoone", "name": "gitCommit", "col": "git_commit_id", "type": "GitCommit", "optional": False},
            {"kind": "string", "name": "shaHash", "col": "sha_hash", "length": 64, "nullable": False},
            {"kind": "string", "name": "policyVersion", "col": "policy_version", "length": 64, "nullable": False},
            {"kind": "enum", "name": "reviewMode", "col": "review_mode", "enum": "CommitReviewMode", "nullable": False},
            {"kind": "string", "name": "traceabilityStatus", "col": "traceability_status", "length": 32, "nullable": False},
            {"kind": "string", "name": "messageQuality", "col": "message_quality", "length": 16, "nullable": False},
            {"kind": "string", "name": "codeQuality", "col": "code_quality", "length": 32, "nullable": False},
            {"kind": "string", "name": "inferredFunctionLabel", "col": "inferred_function_label", "length": 32},
            {"kind": "string", "name": "inferredFunctionConfidence", "col": "inferred_function_confidence", "length": 16},
            {"kind": "string", "name": "taskAlignment", "col": "task_alignment", "length": 32, "nullable": False},
            {"kind": "bool", "name": "verdictEligible", "col": "verdict_eligible", "nullable": False},
            {"kind": "string", "name": "verdict", "col": "verdict", "length": 32, "nullable": False},
            {"kind": "string", "name": "overallStatus", "col": "overall_status", "length": 32, "nullable": False},
            {"kind": "string", "name": "schemaVersion", "col": "schema_version", "length": 64, "nullable": False},
            {"kind": "string", "name": "findingsJson", "col": "findings_json", "columnDefinition": "MEDIUMTEXT"},
            {"kind": "string", "name": "evidenceRefsJson", "col": "evidence_refs_json", "columnDefinition": "MEDIUMTEXT"},
            {"kind": "dt", "name": "completedAt", "col": "completed_at", "nullable": False},
        ],
    },
    {
        "name": "IdentityMap",
        "table": "identity_map",
        "unique": [
            {"name": "uk_identity_user_provider", "cols": ["user_account_id", "provider"]},
            {"name": "uk_identity_provider_external", "cols": ["provider", "external_account_id"]},
        ],
        "fields": [
            {"kind": "manytoone", "name": "userAccount", "col": "user_account_id", "type": "UserAccount", "optional": False},
            {"kind": "enum", "name": "provider", "col": "provider", "enum": "IntegrationProvider", "nullable": False},
            {"kind": "string", "name": "externalAccountId", "col": "external_account_id", "length": 255},
            {"kind": "string", "name": "externalUsername", "col": "external_username", "length": 255},
            {"kind": "string", "name": "externalEmail", "col": "external_email", "length": 255},
            {"kind": "enum", "name": "mappingStatus", "col": "mapping_status", "enum": "IdentityMappingStatus", "nullable": False},
            {"kind": "dt", "name": "verifiedAt", "col": "verified_at"},
            {"kind": "dt", "name": "disconnectedAt", "col": "disconnected_at"},
            {"kind": "manytoone", "name": "reviewedBy", "col": "reviewed_by_user_id", "type": "UserAccount"},
            {"kind": "dt", "name": "reviewedAt", "col": "reviewed_at"},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "IdentityMappingHistory",
        "table": "identity_mapping_history",
        "indexes": [{"name": "ix_identity_history_map", "cols": ["identity_map_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "identityMap", "col": "identity_map_id", "type": "IdentityMap", "optional": False},
            {"kind": "manytoone", "name": "userAccount", "col": "user_account_id", "type": "UserAccount", "optional": False},
            {"kind": "enum", "name": "provider", "col": "provider", "enum": "IntegrationProvider", "nullable": False},
            {"kind": "string", "name": "externalAccountId", "col": "external_account_id", "length": 255, "nullable": False},
            {"kind": "enum", "name": "action", "col": "action", "enum": "IdentityMappingAction", "nullable": False},
            {"kind": "manytoone", "name": "actorUser", "col": "actor_user_id", "type": "UserAccount"},
            {"kind": "dt", "name": "occurredAt", "col": "occurred_at", "nullable": False},
        ],
    },
    {
        "name": "WebhookReceipt",
        "table": "webhook_receipt",
        "unique": [{"name": "uk_webhook_provider_delivery", "cols": ["provider", "delivery_id"]}],
        "indexes": [{"name": "ix_webhook_status", "cols": ["receipt_status", "created_at"]}],
        "fields": [
            {"kind": "enum", "name": "provider", "col": "provider", "enum": "IntegrationProvider", "nullable": False},
            {"kind": "string", "name": "deliveryId", "col": "delivery_id", "length": 128, "nullable": False},
            {"kind": "string", "name": "eventType", "col": "event_type", "length": 128, "nullable": False},
            {"kind": "uuid", "name": "targetId", "col": "target_id"},
            {"kind": "string", "name": "payloadJson", "col": "payload_json", "columnDefinition": "LONGTEXT"},
            {"kind": "enum", "name": "receiptStatus", "col": "receipt_status", "enum": "WebhookReceiptStatus", "nullable": False},
            {"kind": "int", "name": "attemptCount", "col": "attempt_count", "nullable": False},
            {"kind": "dt", "name": "processedAt", "col": "processed_at"},
            {"kind": "string", "name": "errorCategory", "col": "error_category", "length": 64},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "SyncJobLog",
        "table": "sync_job_log",
        "indexes": [{"name": "ix_sync_job_status", "cols": ["status", "started_at"]}],
        "fields": [
            {"kind": "string", "name": "targetSystem", "col": "target_system", "length": 32},
            {"kind": "uuid", "name": "targetId", "col": "target_id"},
            {"kind": "enum", "name": "jobType", "col": "job_type", "enum": "SyncJobType", "length": 64},
            {"kind": "enum", "name": "status", "col": "status", "enum": "SyncJobStatus"},
            {"kind": "string", "name": "errorMessage", "col": "error_message", "columnDefinition": "TEXT"},
            {"kind": "string", "name": "errorCategory", "col": "error_category", "length": 128},
            {"kind": "string", "name": "failureStage", "col": "failure_stage", "length": 64},
            {"kind": "dt", "name": "startedAt", "col": "started_at"},
            {"kind": "dt", "name": "completedAt", "col": "completed_at"},
            {"kind": "int", "name": "itemsProcessed", "col": "items_processed"},
            {"kind": "int", "name": "itemsFailed", "col": "items_failed"},
            {"kind": "dt", "name": "cursorBefore", "col": "cursor_before"},
            {"kind": "dt", "name": "cursorAfter", "col": "cursor_after"},
        ],
    },
    {
        "name": "RubricTemplate",
        "table": "rubric_template",
        "fields": [
            {"kind": "manytoone", "name": "subject", "col": "subject_id", "type": "Subject"},
            {"kind": "string", "name": "criteriaName", "col": "criteria_name", "length": 255},
            {"kind": "decimal", "name": "weight", "col": "weight"},
            {"kind": "string", "name": "description", "col": "description", "length": 1000},
            {"kind": "dt", "name": "deletedAt", "col": "deleted_at"},
        ],
    },
    {
        "name": "PeerReview",
        "table": "peer_review",
        "unique": [{"name": "uk_peer_review_sprint_pair", "cols": ["sprint_id", "reviewer_student_id", "reviewee_student_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "sprint", "col": "sprint_id", "type": "Sprint", "optional": False},
            {"kind": "manytoone", "name": "reviewerStudent", "col": "reviewer_student_id", "type": "StudentProfile", "optional": False},
            {"kind": "manytoone", "name": "revieweeStudent", "col": "reviewee_student_id", "type": "StudentProfile", "optional": False},
            {"kind": "int", "name": "starRating", "col": "star_rating"},
            {"kind": "string", "name": "comment", "col": "comment", "columnDefinition": "TEXT"},
        ],
    },
    {
        "name": "PeerReviewDetail",
        "table": "peer_review_detail",
        "indexes": [{"name": "ix_peer_review_detail_review", "cols": ["peer_review_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "peerReview", "col": "peer_review_id", "type": "PeerReview", "optional": False},
            {"kind": "manytoone", "name": "rubric", "col": "rubric_id", "type": "RubricTemplate", "optional": False},
            {"kind": "string", "name": "criteriaName", "col": "criteria_name", "length": 255, "nullable": False},
            {"kind": "int", "name": "criteriaOrder", "col": "criteria_order", "nullable": False},
            {"kind": "int", "name": "starRating", "col": "star_rating", "nullable": False},
        ],
    },
    {
        "name": "ProjectGroupWeightConfig",
        "table": "project_group_weight_config",
        "unique": [{"name": "uk_weight_config_project", "cols": ["project_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project", "optional": False},
            {"kind": "manytoone", "name": "team", "col": "team_id", "type": "Team", "optional": False},
            {"kind": "decimal", "name": "codeWeight", "col": "code_weight", "precision": 6, "scale": 5, "nullable": False},
            {"kind": "decimal", "name": "testWeight", "col": "test_weight", "precision": 6, "scale": 5, "nullable": False},
            {"kind": "decimal", "name": "documentWeight", "col": "document_weight", "precision": 6, "scale": 5, "nullable": False},
            {"kind": "decimal", "name": "researchWeight", "col": "research_weight", "precision": 6, "scale": 5, "nullable": False},
            {"kind": "string", "name": "note", "col": "note", "length": 1000},
            {"kind": "manytoone", "name": "updatedBy", "col": "updated_by_user_id", "type": "UserAccount"},
        ],
    },
    {
        "name": "ContributionOverride",
        "table": "contribution_override",
        "indexes": [{"name": "ix_contribution_override_course", "cols": ["course_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course", "optional": False},
            {"kind": "manytoone", "name": "team", "col": "team_id", "type": "Team"},
            {"kind": "manytoone", "name": "studentProfile", "col": "student_profile_id", "type": "StudentProfile"},
            {"kind": "string", "name": "overrideType", "col": "override_type", "length": 64, "nullable": False},
            {"kind": "decimal", "name": "oldValue", "col": "old_value"},
            {"kind": "decimal", "name": "newValue", "col": "new_value"},
            {"kind": "string", "name": "reason", "col": "reason", "columnDefinition": "TEXT"},
            {"kind": "manytoone", "name": "createdBy", "col": "created_by_user_id", "type": "UserAccount", "optional": False},
        ],
    },
    {
        "name": "AssessmentRun",
        "table": "assessment_run",
        "indexes": [{"name": "ix_assessment_run_course_sprint", "cols": ["course_id", "sprint_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course", "optional": False},
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project"},
            {"kind": "manytoone", "name": "sprint", "col": "sprint_id", "type": "Sprint"},
            {"kind": "enum", "name": "runType", "col": "run_type", "enum": "AssessmentRunType", "nullable": False},
            {"kind": "string", "name": "calculationVersion", "col": "calculation_version", "length": 64},
            {"kind": "enum", "name": "status", "col": "status", "enum": "AssessmentRunStatus", "nullable": False},
            {"kind": "dt", "name": "startedAt", "col": "started_at"},
            {"kind": "dt", "name": "completedAt", "col": "completed_at"},
        ],
    },
    {
        "name": "AssessmentResult",
        "table": "assessment_result",
        "unique": [{"name": "uk_assessment_result_run_student", "cols": ["assessment_run_id", "student_profile_id"]}],
        "fields": [
            {"kind": "manytoone", "name": "assessmentRun", "col": "assessment_run_id", "type": "AssessmentRun", "optional": False},
            {"kind": "manytoone", "name": "studentProfile", "col": "student_profile_id", "type": "StudentProfile", "optional": False},
            {"kind": "decimal", "name": "contributionScore", "col": "contribution_score"},
            {"kind": "decimal", "name": "peerReviewScore", "col": "peer_review_score"},
            {"kind": "decimal", "name": "finalScore", "col": "final_score"},
            {"kind": "json", "name": "breakdownJson", "col": "breakdown_json"},
            {"kind": "dt", "name": "calculatedAt", "col": "calculated_at", "nullable": False},
        ],
    },
    {
        "name": "NotificationBroadcast",
        "table": "notification_broadcast",
        "unique": [{"name": "uk_broadcast_sender_key", "cols": ["sender_user_id", "idempotency_key"]}],
        "fields": [
            {"kind": "manytoone", "name": "senderUser", "col": "sender_user_id", "type": "UserAccount", "optional": False},
            {"kind": "enum", "name": "audience", "col": "audience", "enum": "BroadcastAudience", "length": 64, "nullable": False},
            {"kind": "string", "name": "title", "col": "title", "length": 160, "nullable": False},
            {"kind": "string", "name": "message", "col": "message", "length": 1000, "nullable": False},
            {"kind": "string", "name": "idempotencyKey", "col": "idempotency_key", "length": 128, "nullable": False},
            {"kind": "string", "name": "requestFingerprint", "col": "request_fingerprint", "length": 64, "nullable": False},
            {"kind": "enum", "name": "status", "col": "status", "enum": "BroadcastStatus", "nullable": False},
            {"kind": "int", "name": "recipientCount", "col": "recipient_count", "nullable": False},
            {"kind": "int", "name": "notificationCount", "col": "notification_count", "nullable": False},
            {"kind": "int", "name": "deliveryQueuedCount", "col": "delivery_queued_count", "nullable": False},
            {"kind": "dt", "name": "completedAt", "col": "completed_at"},
        ],
    },
    {
        "name": "UserNotification",
        "table": "user_notification",
        "unique": [
            {"name": "uk_user_notification_broadcast_recipient", "cols": ["broadcast_id", "recipient_user_id"]},
            {"name": "uk_user_notification_recipient_event", "cols": ["recipient_user_id", "event_key"]},
        ],
        "indexes": [{"name": "ix_user_notification_inbox", "cols": ["recipient_user_id", "created_at"]}],
        "fields": [
            {"kind": "manytoone", "name": "recipientUser", "col": "recipient_user_id", "type": "UserAccount", "optional": False},
            {"kind": "manytoone", "name": "broadcast", "col": "broadcast_id", "type": "NotificationBroadcast"},
            {"kind": "enum", "name": "notificationType", "col": "notification_type", "enum": "NotificationType", "length": 64, "nullable": False},
            {"kind": "string", "name": "title", "col": "title", "length": 160, "nullable": False},
            {"kind": "string", "name": "message", "col": "message", "length": 1000, "nullable": False},
            {"kind": "string", "name": "actionUrl", "col": "action_url", "length": 500},
            {"kind": "string", "name": "eventKey", "col": "event_key", "length": 255},
            {"kind": "dt", "name": "readAt", "col": "read_at"},
        ],
    },
    {
        "name": "FirebaseInstallation",
        "table": "firebase_installation",
        "unique": [{"name": "uk_firebase_installation_fid", "cols": ["firebase_installation_id"]}],
        "indexes": [{"name": "ix_firebase_owner", "cols": ["owner_user_id", "active"]}],
        "fields": [
            {"kind": "manytoone", "name": "ownerUser", "col": "owner_user_id", "type": "UserAccount", "optional": False},
            {"kind": "string", "name": "firebaseInstallationId", "col": "firebase_installation_id", "length": 255, "nullable": False},
            {"kind": "bool", "name": "active", "col": "active", "nullable": False},
            {"kind": "dt", "name": "lastRegisteredAt", "col": "last_registered_at", "nullable": False},
            {"kind": "dt", "name": "revokedAt", "col": "revoked_at"},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "NotificationDelivery",
        "table": "notification_delivery",
        "unique": [{"name": "uk_notification_delivery_installation", "cols": ["notification_id", "installation_id"]}],
        "indexes": [{"name": "ix_delivery_status", "cols": ["delivery_status"]}],
        "fields": [
            {"kind": "manytoone", "name": "notification", "col": "notification_id", "type": "UserNotification", "optional": False},
            {"kind": "manytoone", "name": "installation", "col": "installation_id", "type": "FirebaseInstallation", "optional": False},
            {"kind": "enum", "name": "deliveryStatus", "col": "delivery_status", "enum": "DeliveryStatus", "nullable": False},
            {"kind": "int", "name": "attemptCount", "col": "attempt_count", "nullable": False},
            {"kind": "dt", "name": "lastAttemptAt", "col": "last_attempt_at"},
            {"kind": "dt", "name": "processingStartedAt", "col": "processing_started_at"},
            {"kind": "dt", "name": "sentAt", "col": "sent_at"},
            {"kind": "string", "name": "failureCode", "col": "failure_code", "length": 64},
            {"kind": "version", "name": "version", "col": "version"},
        ],
    },
    {
        "name": "EmailOutbox",
        "table": "email_outbox",
        "indexes": [{"name": "ix_email_outbox_status", "cols": ["delivery_status", "scheduled_at"]}],
        "fields": [
            {"kind": "manytoone", "name": "recipientUser", "col": "recipient_user_id", "type": "UserAccount"},
            {"kind": "string", "name": "recipientEmail", "col": "recipient_email", "length": 255, "nullable": False},
            {"kind": "string", "name": "emailType", "col": "email_type", "length": 64, "nullable": False},
            {"kind": "string", "name": "templateKey", "col": "template_key", "length": 128},
            {"kind": "json", "name": "payloadJson", "col": "payload_json"},
            {"kind": "enum", "name": "deliveryStatus", "col": "delivery_status", "enum": "EmailDeliveryStatus", "nullable": False},
            {"kind": "int", "name": "attemptCount", "col": "attempt_count", "nullable": False},
            {"kind": "dt", "name": "scheduledAt", "col": "scheduled_at"},
            {"kind": "dt", "name": "sentAt", "col": "sent_at"},
            {"kind": "string", "name": "lastFailureCode", "col": "last_failure_code", "length": 64},
            {"kind": "dt", "name": "lastAttemptAt", "col": "last_attempt_at"},
        ],
    },
    {
        "name": "BusinessWarning",
        "table": "business_warning",
        "unique": [{"name": "uk_business_warning_event", "cols": ["event_key"]}],
        "indexes": [{"name": "ix_business_warning_course", "cols": ["course_id"]}],
        "fields": [
            {"kind": "string", "name": "warningType", "col": "warning_type", "length": 64, "nullable": False},
            {"kind": "enum", "name": "category", "col": "category", "enum": "WarningCategory", "nullable": False},
            {"kind": "string", "name": "eventKey", "col": "event_key", "length": 255, "nullable": False},
            {"kind": "enum", "name": "severity", "col": "severity", "enum": "WarningSeverity"},
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course"},
            {"kind": "manytoone", "name": "team", "col": "team_id", "type": "Team"},
            {"kind": "manytoone", "name": "project", "col": "project_id", "type": "Project"},
            {"kind": "manytoone", "name": "sprint", "col": "sprint_id", "type": "Sprint"},
            {"kind": "manytoone", "name": "studentProfile", "col": "student_profile_id", "type": "StudentProfile"},
            {"kind": "string", "name": "commitSha", "col": "commit_sha", "length": 64},
            {"kind": "string", "name": "evidenceSummary", "col": "evidence_summary", "length": 1000, "nullable": False},
            {"kind": "enum", "name": "progressMode", "col": "progress_mode", "enum": "SprintProgressMode"},
        ],
    },
    {
        "name": "AiAgentDelegationContext",
        "table": "ai_agent_delegation_context",
        "unique": [{"name": "uk_ai_delegation_token", "cols": ["token_hash"]}],
        "fields": [
            {"kind": "string", "name": "tokenHash", "col": "token_hash", "length": 64, "nullable": False},
            {"kind": "uuid", "name": "conversationId", "col": "conversation_id", "nullable": False},
            {"kind": "manytoone", "name": "actorUser", "col": "actor_user_id", "type": "UserAccount", "optional": False},
            {"kind": "enum", "name": "actorApplicationRole", "col": "actor_application_role", "enum": "AccountRole", "nullable": False},
            {"kind": "string", "name": "capabilities", "col": "capabilities", "length": 128, "nullable": False},
            {"kind": "dt", "name": "expiresAt", "col": "expires_at", "nullable": False},
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course"},
        ],
    },
    {
        "name": "AiAgentConversationScope",
        "table": "ai_agent_conversation_scope",
        "unique": [{"name": "uk_ai_conversation_id", "cols": ["conversation_id"]}],
        "fields": [
            {"kind": "uuid", "name": "conversationId", "col": "conversation_id", "nullable": False},
            {"kind": "manytoone", "name": "course", "col": "course_id", "type": "Course", "optional": False},
            {"kind": "manytoone", "name": "ownerUser", "col": "owner_user_id", "type": "UserAccount", "optional": False},
            {"kind": "enum", "name": "ownerApplicationRole", "col": "owner_application_role", "enum": "AccountRole", "nullable": False},
        ],
    },
    {
        "name": "GraphProcessingRun",
        "table": "graph_processing_run",
        "base": False,
        "custom_id": True,
        "indexes": [
            {"name": "ix_graph_processing_run_occurred_at", "cols": ["occurred_at"]},
            {"name": "ix_graph_processing_run_kind_occurred_at", "cols": ["graph_kind", "occurred_at"]},
        ],
        "fields": [
            {"kind": "enum", "name": "graphKind", "col": "graph_kind", "enum": "GraphProcessingKind", "nullable": False},
            {"kind": "dt", "name": "occurredAt", "col": "occurred_at", "nullable": False},
            {"kind": "uuid", "name": "courseId", "col": "course_id"},
            {"kind": "uuid", "name": "teamId", "col": "team_id"},
            {"kind": "uuid", "name": "studentProfileId", "col": "student_profile_id"},
            {"kind": "int", "name": "nodesBuilt", "col": "nodes_built", "nullable": False},
            {"kind": "int", "name": "edgesBuilt", "col": "edges_built", "nullable": False},
        ],
    },
    {
        "name": "OutboxEvent",
        "table": "outbox_event",
        "indexes": [{"name": "ix_outbox_status_available", "cols": ["status", "available_at"]}],
        "fields": [
            {"kind": "string", "name": "aggregateType", "col": "aggregate_type", "length": 64, "nullable": False},
            {"kind": "uuid", "name": "aggregateId", "col": "aggregate_id", "nullable": False},
            {"kind": "string", "name": "eventType", "col": "event_type", "length": 128, "nullable": False},
            {"kind": "json", "name": "payload", "col": "payload", "nullable": False},
            {"kind": "enum", "name": "status", "col": "status", "enum": "OutboxStatus", "nullable": False},
            {"kind": "int", "name": "attemptCount", "col": "attempt_count", "nullable": False},
            {"kind": "dt", "name": "availableAt", "col": "available_at", "nullable": False},
            {"kind": "dt", "name": "processedAt", "col": "processed_at"},
            {"kind": "string", "name": "lastError", "col": "last_error", "length": 1000},
        ],
    },
]


def main() -> None:
    written = []
    for spec in ENTITIES:
        pkg = PACKAGES[spec["name"]]
        directory = ROOT / pkg
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{spec['name']}.java"
        path.write_text(render_entity(spec), encoding="utf-8")
        written.append(str(path.relative_to(ROOT.parent.parent.parent.parent)))
    print(f"Wrote {len(written)} entities")
    assert len(ENTITIES) == 52, len(ENTITIES)


if __name__ == "__main__":
    main()
