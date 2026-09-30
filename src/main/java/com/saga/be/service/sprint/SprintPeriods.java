package com.saga.be.service.sprint;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.service.projection.ProjectionMappings;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint scheduling rule for projects that may use several Jira sites: contribution, peer review
 * and the "current sprint" are all per sprint, so across ALL sites of a project the sprints must
 * run one after another, never at the same time.
 *
 * <p>Periods are compared by calendar date with the end day exclusive, so a sprint ending on day D
 * and the next one starting on day D do not overlap (hand-over day). A closed sprint ends on its
 * real complete date. An active sprint of a live source is still running today, so its period
 * extends to at least tomorrow even when its planned end has passed. A sprint with no start date,
 * or no usable end, has no period and never conflicts.
 */
public final class SprintPeriods {

	public record Period(LocalDate start, LocalDate endExclusive) {
		public boolean overlaps(Period other) {
			return other != null && start.isBefore(other.endExclusive) && other.start.isBefore(endExclusive);
		}
	}

	/** Two sprints whose periods overlap; {@code first} starts no later than {@code second}. */
	public record Overlap(Sprint first, Sprint second) {
		/** Stable per pair regardless of order, so one pair is reported only once. */
		public String pairKey() {
			UUID a = first.getId();
			UUID b = second.getId();
			return a.toString().compareTo(b.toString()) <= 0 ? a + ":" + b : b + ":" + a;
		}
	}

	private SprintPeriods() {}

	public static boolean isActive(String state) {
		return "active".equals(normalize(state));
	}

	public static boolean isClosed(String state) {
		return "closed".equals(normalize(state));
	}

	public static boolean isStarted(String state) {
		return isActive(state) || isClosed(state);
	}

	public static Period of(Sprint sprint, LocalDate today) {
		return of(
				sprint.getState(),
				sprint.getStartDate(),
				sprint.getEndDate(),
				sprint.getCompleteDate(),
				sourceIsLive(sprint),
				today);
	}

	public static Period of(
			String state,
			LocalDateTime start,
			LocalDateTime end,
			LocalDateTime complete,
			boolean sourceLive,
			LocalDate today) {
		if (start == null) {
			return null;
		}
		LocalDate from = start.toLocalDate();
		LocalDateTime rawEnd = isClosed(state) && complete != null ? complete : end;
		LocalDate to = rawEnd == null ? null : rawEnd.toLocalDate();
		if (isActive(state) && sourceLive) {
			LocalDate stillRunning = today.plusDays(1);
			if (to == null || to.isBefore(stillRunning)) {
				to = stillRunning;
			}
		}
		if (to == null || !to.isAfter(from)) {
			return null;
		}
		return new Period(from, to);
	}

	/**
	 * Every overlapping pair among {@code sprints}. {@code startedOnly} keeps only pairs where both
	 * sprints have actually run (active or closed) -- those are the ones that already affect scoring.
	 */
	public static List<Overlap> overlaps(List<Sprint> sprints, LocalDate today, boolean startedOnly) {
		List<Sprint> dated = new ArrayList<>();
		for (Sprint sprint : sprints) {
			if (sprint == null || sprint.getId() == null || sprint.getDeletedAt() != null) {
				continue;
			}
			if (startedOnly && !isStarted(sprint.getState())) {
				continue;
			}
			if (of(sprint, today) != null) {
				dated.add(sprint);
			}
		}
		dated.sort(byPeriodStart(today));
		List<Overlap> result = new ArrayList<>();
		for (int i = 0; i < dated.size(); i++) {
			Period left = of(dated.get(i), today);
			for (int j = i + 1; j < dated.size(); j++) {
				if (left.overlaps(of(dated.get(j), today))) {
					result.add(new Overlap(dated.get(i), dated.get(j)));
				}
			}
		}
		return result;
	}

	/** The earliest-starting sprint in {@code others} (excluding {@code selfId}) that {@code candidate} overlaps. */
	public static Optional<Sprint> firstConflict(Period candidate, List<Sprint> others, UUID selfId, LocalDate today) {
		if (candidate == null) {
			return Optional.empty();
		}
		return others.stream()
				.filter(sprint -> sprint != null && sprint.getDeletedAt() == null)
				.filter(sprint -> selfId == null || !selfId.equals(sprint.getId()))
				.filter(sprint -> candidate.overlaps(of(sprint, today)))
				.min(byPeriodStart(today));
	}

	/** Accepts Jira instants, ISO local date-times and plain {@code yyyy-MM-dd} dates. */
	public static LocalDateTime parseRequestDate(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		LocalDateTime parsed = ProjectionMappings.parseInstant(raw);
		if (parsed != null) {
			return parsed;
		}
		try {
			return LocalDate.parse(raw.trim()).atStartOfDay();
		} catch (Exception ignored) {
			return null;
		}
	}

	private static boolean sourceIsLive(Sprint sprint) {
		return sprint.getJiraIntegration() == null
				|| sprint.getJiraIntegration().getConnectionStatus() == IntegrationStatus.ACTIVE;
	}

	private static Comparator<Sprint> byPeriodStart(LocalDate today) {
		return Comparator.comparing((Sprint sprint) -> {
					Period period = of(sprint, today);
					return period == null ? LocalDate.MAX : period.start();
				})
				.thenComparing(sprint -> sprint.getId().toString());
	}

	private static String normalize(String state) {
		return state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
	}
}
