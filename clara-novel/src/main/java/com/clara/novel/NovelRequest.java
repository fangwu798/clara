package com.clara.novel;

import java.nio.file.Path;

/** User requirements for one resumable novel project. */
public final class NovelRequest {
    private final String title;
    private final String genre;
    private final String premise;
    private final int chapterCount;
    private final int wordsPerChapter;
    private final Path outputDirectory;

    public NovelRequest(String title, String genre, String premise, int chapterCount,
            int wordsPerChapter, Path outputDirectory) {
        if (blank(title) || blank(genre) || blank(premise) || chapterCount <= 0
                || chapterCount > 100 || wordsPerChapter < 300 || wordsPerChapter > 20000
                || outputDirectory == null) {
            throw new IllegalArgumentException("novel request is invalid");
        }
        this.title = title.trim();
        this.genre = genre.trim();
        this.premise = premise.trim();
        this.chapterCount = chapterCount;
        this.wordsPerChapter = wordsPerChapter;
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
    }

    public String getTitle() { return title; }
    public String getGenre() { return genre; }
    public String getPremise() { return premise; }
    public int getChapterCount() { return chapterCount; }
    public int getWordsPerChapter() { return wordsPerChapter; }
    public Path getOutputDirectory() { return outputDirectory; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
