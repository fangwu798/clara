package com.clara.workspace.api;
import java.util.Collections;import java.util.LinkedHashMap;import java.util.Map;
/**
 * 表示。
 */
public final class DocumentNode {private final String nodeId,text;private final DocumentNodeType type;private final Map<String,String> metadata;
 public DocumentNode(String id,DocumentNodeType type,String text,Map<String,String> metadata){if(id==null||type==null) {throw new IllegalArgumentException(); }nodeId=id;this.type=type;this.text=text==null?"":text;this.metadata=metadata==null?Collections.<String,String>emptyMap():Collections.unmodifiableMap(new LinkedHashMap<String,String>(metadata));}
 public String getNodeId(){return nodeId;}public DocumentNodeType getType(){return type;}public String getText(){return text;}public Map<String,String> getMetadata(){return metadata;}}
