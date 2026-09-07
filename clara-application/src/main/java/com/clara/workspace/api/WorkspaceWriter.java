package com.clara.workspace.api;
/**
 * 写入工作区数据。
 */
public interface WorkspaceWriter {ChangePreview preview(WorkspaceRef workspace,ChangeSet changeSet);ApplyResult apply(WorkspaceRef workspace,ChangeSet changeSet,Approval approval);ApplyResult recover(WorkspaceRef workspace,String runId,String changeSetId,int revision);}
