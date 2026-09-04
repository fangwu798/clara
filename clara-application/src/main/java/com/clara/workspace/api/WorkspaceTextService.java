package com.clara.workspace.api;
public interface WorkspaceTextService {TextSearchResult search(WorkspaceRef workspace,TextSearchRequest request);ChangeSet replace(WorkspaceRef workspace,TextEditSpec edit);ChangeSet deleteText(WorkspaceRef workspace,TextEditSpec edit);}
