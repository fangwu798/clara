package com.clara.workspace.api;

import java.util.*;

/**
 * 表示。
 */
public final class TextEditSpec {
    private final TextSearchRequest search;
    private final TextEditAction action;
    private final String replacement;
    private final Set<String> matchIds;
    private final Map<String, String> expectedFileHashes;
    private final int expectedMatches, maxReplacements;

    public TextEditSpec(TextSearchRequest search, TextEditAction action, String replacement, Set<String> matchIds, int expected, int maximum) {
        this(search, action, replacement, matchIds, expected, maximum, null);
    }

    public TextEditSpec(TextSearchRequest search, TextEditAction action, String replacement, Set<String> matchIds, int expected, int maximum, Map<String, String> hashes) {
        if (search == null || action == null || expected < 0 || maximum <= 0) {
            throw new IllegalArgumentException("edit spec is invalid");
        }
        this.search = search;
        this.action = action;
        this.replacement = replacement == null ? "" : replacement;
        this.matchIds = matchIds == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(new LinkedHashSet<String>(matchIds));
        this.expectedFileHashes = hashes == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<String, String>(hashes));
        expectedMatches = expected;
        maxReplacements = maximum;
    }

    public TextSearchRequest getSearch() {
        return search;
    }

    public TextEditAction getAction() {
        return action;
    }

    public String getReplacement() {
        return replacement;
    }

    public Set<String> getMatchIds() {
        return matchIds;
    }

    public Map<String, String> getExpectedFileHashes() {
        return expectedFileHashes;
    }

    public int getExpectedMatches() {
        return expectedMatches;
    }

    public int getMaxReplacements() {
        return maxReplacements;
    }
}
