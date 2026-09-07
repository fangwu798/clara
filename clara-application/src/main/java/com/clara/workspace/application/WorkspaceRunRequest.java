package com.clara.workspace.application;
import com.clara.workspace.api.WorkspaceAccessMode;import java.nio.file.Path;import java.util.*;
/**
 * 封装工作区运行操作的输入参数。
 */
public final class WorkspaceRunRequest {private final String tenantId,userId,runId,goal,skillId;private final Path workspace;private final WorkspaceAccessMode accessMode;private final boolean approveChanges;private final Map<String,Object> inputs;
 public WorkspaceRunRequest(String tenant,String user,String run,Path workspace,String goal,String skill,WorkspaceAccessMode mode,boolean approve,Map<String,Object> inputs){if(blank(tenant)||blank(user)||blank(run)||workspace==null||!workspace.isAbsolute()||mode==null) {throw new IllegalArgumentException("workspace run request is invalid"); }tenantId=tenant;userId=user;runId=run;this.workspace=workspace.normalize();this.goal=goal;skillId=skill;accessMode=mode;approveChanges=approve;this.inputs=inputs==null?Collections.<String,Object>emptyMap():Collections.unmodifiableMap(new LinkedHashMap<String,Object>(inputs));}
 public String getTenantId(){return tenantId;}public String getUserId(){return userId;}public String getRunId(){return runId;}public Path getWorkspace(){return workspace;}public String getGoal(){return goal;}public String getSkillId(){return skillId;}public WorkspaceAccessMode getAccessMode(){return accessMode;}public boolean isApproveChanges(){return approveChanges;}public Map<String,Object> getInputs(){return inputs;}private static boolean blank(String v){return v==null||v.trim().isEmpty();}}
