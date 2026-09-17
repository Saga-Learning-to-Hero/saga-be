package com.saga.be.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import org.junit.jupiter.api.Test;

class GraphViewQueryTest {

	@Test
	void includeCommitsSkipsDefaultCompact() {
		GraphViewQuery query = GraphViewQuery.parse(null, null, null, null, null, null, null, null, true);
		assertThat(query.skipDefaultCompact()).isTrue();
		assertThat(query.active()).isFalse();
		assertThat(query.withDefaultCompact(GraphViewQuery.compactOverview())).isSameAs(query);
	}

	@Test
	void usedCriteriaOnlyActivatesFilter() {
		GraphViewQuery query = GraphViewQuery.parse(null, null, null, null, null, null, null, null, null, true);
		assertThat(query.active()).isTrue();
		assertThat(query.usedCriteriaOnly()).isTrue();
		GraphViewQuery off = GraphViewQuery.parse(null, null, null, null, null, null, null, null, null, false);
		assertThat(off.active()).isFalse();
	}

	@Test
	void absentParamsStayInactiveForOldContract() {
		GraphViewQuery query = GraphViewQuery.parse(null, 2, null, null, false, null, null);
		assertThat(query.active()).isFalse();
		assertThat(query.viewKey()).isEmpty();
	}

	@Test
	void continuationTokenIsAliasForCursor() {
		GraphViewQuery fromCursor = GraphViewQuery.parse(null, null, "TASK", null, null, null, "9:task:a", null);
		GraphViewQuery fromToken = GraphViewQuery.parse(null, null, "TASK", null, null, null, null, "9:task:a");
		assertThat(fromToken.cursor()).isEqualTo("9:task:a");
		assertThat(fromToken.viewKey()).isEqualTo(fromCursor.viewKey());
	}

	@Test
	void unknownNodeTypeIsRejected() {
		assertThatThrownBy(() -> GraphViewQuery.parse(null, null, "TASK,PR", null, null, null, null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
	}

	@Test
	void unknownEdgeTypeIsRejected() {
		assertThatThrownBy(() -> GraphViewQuery.parse(null, null, null, "IMPLEMENTS", null, null, null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
	}

	@Test
	void depthAndMaxNodesAreBounded() {
		assertThatThrownBy(() -> GraphViewQuery.parse("task:1", 4, null, null, null, null, null))
				.isInstanceOf(AcademicException.class);
		assertThatThrownBy(() -> GraphViewQuery.parse(null, null, "TASK", null, null, 0, null))
				.isInstanceOf(AcademicException.class);
		assertThatThrownBy(() -> GraphViewQuery.parse(null, null, "TASK", null, null, 2001, null))
				.isInstanceOf(AcademicException.class);
		assertThat(GraphViewQuery.parse("task:1", 3, null, null, null, 2000, null).depth()).isEqualTo(3);
	}
}
