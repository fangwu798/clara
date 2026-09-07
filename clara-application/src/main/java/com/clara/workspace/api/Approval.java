package com.clara.workspace.api;
import java.util.*;
/**
 * 表示用户对工作区变更的审批信息。
 */
public final class Approval {
 private final String changeSetId,changeSetDigest,previewDigest,approvedBy;private final int revision;private final long approvedAtMillis;private final Set<String> paths;
 public Approval(String id,int revision,String digest,String previewDigest,String approvedBy,long approvedAtMillis,Set<String> paths){if(blank(id)||revision<=0||blank(digest)||blank(previewDigest)||blank(approvedBy)||approvedAtMillis<=0) {throw new IllegalArgumentException("approval is invalid"); }this.changeSetId=id;this.revision=revision;this.changeSetDigest=digest;this.previewDigest=previewDigest;this.approvedBy=approvedBy;this.approvedAtMillis=approvedAtMillis;this.paths=paths==null?Collections.<String>emptySet():Collections.unmodifiableSet(new LinkedHashSet<String>(paths));}
 public String getChangeSetId(){return changeSetId;}public int getRevision(){return revision;}public String getChangeSetDigest(){return changeSetDigest;}public String getPreviewDigest(){return previewDigest;}public String getApprovedBy(){return approvedBy;}public long getApprovedAtMillis(){return approvedAtMillis;}public Set<String> getPaths(){return paths;}
 private static boolean blank(String v){return v==null||v.trim().isEmpty();}
}
