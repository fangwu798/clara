package com.clara.novel;

import java.util.ArrayList;
import java.util.List;

/** Persisted state used to resume generation without repeating completed model calls. */
public final class NovelProjectState {
    public int schemaVersion = 1;
    public String title;
    public String genre;
    public String premise;
    public String model;
    public int chapterCount;
    public int wordsPerChapter;
    public String bibleAndOutline;
    public boolean coverAttempted;
    public String coverStatus;
    public String coverPath;
    public String coverModel;
    public String coverError;
    public List<NovelChapter> chapters = new ArrayList<NovelChapter>();
    public boolean completed;

    public static final class NovelChapter {
        public int number;
        public String title;
        public String content;
        public String summary;

        public NovelChapter() { }
        public NovelChapter(int number, String title, String content, String summary) {
            this.number = number;
            this.title = title;
            this.content = content;
            this.summary = summary;
        }
    }
}
