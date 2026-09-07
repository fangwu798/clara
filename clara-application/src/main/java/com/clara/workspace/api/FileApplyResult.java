package com.clara.workspace.api;
/**
 * 封装文件应用操作的执行结果。
 */
public final class FileApplyResult {private final String relativePath,baseHash,resultHash,backupPath;private final FileApplyStatus status;
 public FileApplyResult(String path,String base,String result,String backup,FileApplyStatus status){relativePath=path;baseHash=base;resultHash=result;backupPath=backup;this.status=status;}
 public String getRelativePath(){return relativePath;}public String getBaseHash(){return baseHash;}public String getResultHash(){return resultHash;}public String getBackupPath(){return backupPath;}public FileApplyStatus getStatus(){return status;}}
