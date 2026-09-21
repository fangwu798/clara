package com.clara.memory.semantic.local;

import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ClaraError;
import com.clara.kernel.exception.ErrorCategory;

import java.util.Collections;final class SemanticMemoryErrors{private SemanticMemoryErrors(){}static ClaraException error(String code, ErrorCategory category, boolean retryable){return new ClaraException(new ClaraError(code,category,code,retryable,null,null,null,null,Collections.<String,String>emptyMap()));}}
