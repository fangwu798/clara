package com.clara.workspace.api;
public interface WorkspaceWriter {ChangePreview preview(WorkspaceRef workspace,ChangeSet changeSet);ApplyResult apply(WorkspaceRef workspace,ChangeSet changeSet,Approval approval);ApplyResult recover(WorkspaceRef workspace,String runId,String changeSetId,int revision);}
