package com.clara.workspace.api;

/**
 * 定义文件的查询条件。
 */
public final class FileQuery {
    private final String nameContains;
    private final int maximum;

    public FileQuery(String nameContains, int maximum) {
        if (maximum <= 0) {
            throw new IllegalArgumentException();
        }
        this.nameContains = nameContains;
        this.maximum = maximum;
    }

    public String getNameContains() {
        return nameContains;
    }

    public int getMaximum() {
        return maximum;
    }
}
