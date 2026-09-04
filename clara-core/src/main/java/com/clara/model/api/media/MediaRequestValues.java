package com.clara.model.api.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MediaRequestValues {
    private MediaRequestValues() { }
    static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    static List<String> assetIds(List<String> values, String name) {
        if (values == null) return Collections.emptyList();
        List<String> copy = new ArrayList<String>(values.size());
        Set<String> unique = new HashSet<String>();
        for (String value : values) {
            required(value, name);
            if (!unique.add(value)) throw new IllegalArgumentException(name + " must not contain duplicates");
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }
}
