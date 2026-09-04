package com.clara.workspace.api;
import java.util.*;
public final class TextSearchRequest {private final TextMatchMode mode;private final String expression;private final boolean caseInsensitive;private final int maxFiles,maxResults;
 public TextSearchRequest(TextMatchMode mode,String expression,boolean caseInsensitive,int maxFiles,int maxResults){if(mode==null||expression==null||expression.isEmpty()||maxFiles<=0||maxResults<=0)throw new IllegalArgumentException("search request is invalid");this.mode=mode;this.expression=expression;this.caseInsensitive=caseInsensitive;this.maxFiles=maxFiles;this.maxResults=maxResults;}
 public TextMatchMode getMode(){return mode;}public String getExpression(){return expression;}public boolean isCaseInsensitive(){return caseInsensitive;}public int getMaxFiles(){return maxFiles;}public int getMaxResults(){return maxResults;}}
