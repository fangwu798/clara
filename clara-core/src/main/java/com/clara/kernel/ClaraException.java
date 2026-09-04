package com.clara.kernel;

public class ClaraException extends RuntimeException {
    private final ClaraError error;
    public ClaraException(ClaraError error) {
        super(requireError(error).getSafeMessage());
        this.error = error;
    }
    private static ClaraError requireError(ClaraError error) {
        if (error == null) throw new IllegalArgumentException("error must not be null");
        return error;
    }
    public ClaraError getError() {
        return error;
    }
}
