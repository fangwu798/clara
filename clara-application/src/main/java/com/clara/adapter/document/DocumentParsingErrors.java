package com.clara.adapter.document;
import com.clara.kernel.*;import java.util.Collections;
/**
 * 集中创建文档解析相关的领域错误。
 */
final class DocumentParsingErrors {private DocumentParsingErrors(){}static ClaraException error(String code,ErrorCategory category){return new ClaraException(new ClaraError(code,category,code,false,null,null,null,null,Collections.<String,String>emptyMap()));}}
