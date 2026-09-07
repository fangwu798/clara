package com.clara.workspace.api;
import java.nio.file.Path;
/**
 * 标识对工作区的引用。
 */
public final class WorkspaceRef {
 private final String workspaceId,tenantId,runId;private final Path root;private final WorkspaceAccessMode mode;
 public WorkspaceRef(String workspaceId,String tenantId,String runId,Path root,WorkspaceAccessMode mode){
  if(blank(workspaceId)||blank(tenantId)||blank(runId)||root==null||!root.isAbsolute()||mode==null) {throw new IllegalArgumentException("workspace reference is invalid"); }
  this.workspaceId=workspaceId;this.tenantId=tenantId;this.runId=runId;this.root=root.normalize();this.mode=mode;}
 public String getWorkspaceId(){return workspaceId;}public String getTenantId(){return tenantId;}public String getRunId(){return runId;}
 public Path getRoot(){return root;}public WorkspaceAccessMode getMode(){return mode;}
 private static boolean blank(String v){return v==null||v.trim().isEmpty();}
}
