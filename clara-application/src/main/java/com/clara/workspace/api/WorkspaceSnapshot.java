package com.clara.workspace.api;
import java.util.ArrayList;import java.util.Collections;import java.util.List;
/**
 * 保存工作区在特定时刻的快照。
 */
public final class WorkspaceSnapshot {
 private final List<FileDescriptor> files;private final String fingerprint;private final boolean truncated;private final long totalBytes;
 public WorkspaceSnapshot(List<FileDescriptor> files,String fingerprint,boolean truncated,long totalBytes){this.files=Collections.unmodifiableList(new ArrayList<FileDescriptor>(files));this.fingerprint=fingerprint;this.truncated=truncated;this.totalBytes=totalBytes;}
 public List<FileDescriptor> getFiles(){return files;}public String getFingerprint(){return fingerprint;}public boolean isTruncated(){return truncated;}public long getTotalBytes(){return totalBytes;}
}
