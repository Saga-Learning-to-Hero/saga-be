package com.saga.be.entity.graph;

import com.saga.be.entity.enums.GraphProcessingKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "graph_processing_run",
	indexes = {
		@Index(name = "ix_graph_processing_run_occurred_at", columnList = "occurred_at"),
		@Index(name = "ix_graph_processing_run_kind_occurred_at", columnList = "graph_kind, occurred_at")
	}
)
public class GraphProcessingRun {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@JdbcTypeCode(Types.CHAR)
	@Column(name = "id", columnDefinition = "char(36)", updatable = false, nullable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "graph_kind", length = 32, nullable = false)
	private GraphProcessingKind graphKind;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "course_id", columnDefinition = "char(36)")
	private UUID courseId;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "team_id", columnDefinition = "char(36)")
	private UUID teamId;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "student_profile_id", columnDefinition = "char(36)")
	private UUID studentProfileId;

	@Column(name = "nodes_built", nullable = false)
	private Integer nodesBuilt;

	@Column(name = "edges_built", nullable = false)
	private Integer edgesBuilt;
}
