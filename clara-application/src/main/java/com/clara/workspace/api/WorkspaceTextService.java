package com.clara.workspace.api;
/**
 * 提供工作区文本相关的应用服务。
 */
public interface WorkspaceTextService {TextSearchResult search(WorkspaceRef workspace,TextSearchRequest request);ChangeSet replace(WorkspaceRef workspace,TextEditSpec edit);ChangeSet deleteText(WorkspaceRef workspace,TextEditSpec edit);}
