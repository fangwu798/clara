package com.clara.adapter.document;
import com.clara.kernel.*;import java.util.Collections;
final class DocumentParsingErrors {private DocumentParsingErrors(){}static ClaraException error(String code,ErrorCategory category){return new ClaraException(new ClaraError(code,category,code,false,null,null,null,null,Collections.<String,String>emptyMap()));}}
