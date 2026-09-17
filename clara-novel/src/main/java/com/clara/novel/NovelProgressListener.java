package com.clara.novel;

/** Receives user-facing progress without coupling the agent to a UI. */
public interface NovelProgressListener {
    void onProgress(String message);
}
