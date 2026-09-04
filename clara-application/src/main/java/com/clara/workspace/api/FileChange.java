package com.clara.workspace.api;
public final class FileChange {private final String relativePath,baseHash,resultHash;private final byte[] content;
 public FileChange(String path,String base,String result,byte[] content){if(path==null||path.trim().isEmpty()||base==null||result==null||content==null)throw new IllegalArgumentException("file change is invalid");relativePath=path;baseHash=base;resultHash=result;this.content=content.clone();}
 public String getRelativePath(){return relativePath;}public String getBaseHash(){return baseHash;}public String getResultHash(){return resultHash;}public byte[] getContent(){return content.clone();}}
