package com.clara.skill.api;

public final class SkillArtifact {
    private final String source;
    private final String author;
    private final long publishedAtEpochMillis;
    private final String sha256;
    private final String signature;
    private final String parentVersion;
    public SkillArtifact(String source, String author, long publishedAtEpochMillis,
            String sha256, String signature, String parentVersion) {
        if (blank(source) || blank(author) || blank(sha256) || publishedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("skill artifact is invalid");
        }
        this.source = source; this.author = author; this.publishedAtEpochMillis = publishedAtEpochMillis;
        this.sha256 = sha256; this.signature = signature; this.parentVersion = parentVersion;
    }
    public String getSource() { return source; }
    public String getAuthor() { return author; }
    public long getPublishedAtEpochMillis() { return publishedAtEpochMillis; }
    public String getSha256() { return sha256; }
    public String getSignature() { return signature; }
    public String getParentVersion() { return parentVersion; }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
