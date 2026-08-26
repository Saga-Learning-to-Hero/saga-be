package com.saga.be.service.traceability;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JiraKeyExtractor {

	private static final Pattern KEY = Pattern.compile("([A-Z][A-Z0-9]+-\\d+)", Pattern.CASE_INSENSITIVE);

	private JiraKeyExtractor() {}

	public static Set<String> extract(String... texts) {
		Set<String> keys = new LinkedHashSet<>();
		if (texts == null) {
			return keys;
		}
		for (String text : texts) {
			if (text == null || text.isBlank()) {
				continue;
			}
			Matcher matcher = KEY.matcher(text);
			while (matcher.find()) {
				keys.add(matcher.group(1).toUpperCase(Locale.ROOT));
			}
		}
		return keys;
	}
}
