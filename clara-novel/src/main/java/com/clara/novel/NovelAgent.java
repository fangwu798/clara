package com.clara.novel;

/** Creates or resumes a novel project. */
public interface NovelAgent {
    NovelProjectState create(NovelRequest request);
}
