package com.clara.workspace.api;
import java.util.Collections;import java.util.LinkedHashSet;import java.util.Set;
public final class WorkspacePolicy {
 private final Set<String> extensions,ignoredNames,sensitiveNames;private final int maxFiles;private final long maxFileBytes,maxTotalBytes;private final boolean followLinks;
 public WorkspacePolicy(Set<String> extensions,Set<String> ignoredNames,Set<String> sensitiveNames,int maxFiles,long maxFileBytes,long maxTotalBytes,boolean followLinks){
  if(maxFiles<=0||maxFileBytes<=0||maxTotalBytes<=0)throw new IllegalArgumentException("workspace limits must be positive");
  this.extensions=copy(extensions);this.ignoredNames=copy(ignoredNames);this.sensitiveNames=copy(sensitiveNames);
  this.maxFiles=maxFiles;this.maxFileBytes=maxFileBytes;this.maxTotalBytes=maxTotalBytes;this.followLinks=followLinks;}
 public Set<String> getAllowedExtensions(){return extensions;}public Set<String> getIgnoredNames(){return ignoredNames;}public Set<String> getSensitiveNames(){return sensitiveNames;}
 public int getMaxFiles(){return maxFiles;}public long getMaxFileBytes(){return maxFileBytes;}public long getMaxTotalBytes(){return maxTotalBytes;}public boolean isFollowLinks(){return followLinks;}
 private static Set<String> copy(Set<String> v){return v==null?Collections.<String>emptySet():Collections.unmodifiableSet(new LinkedHashSet<String>(v));}
}
