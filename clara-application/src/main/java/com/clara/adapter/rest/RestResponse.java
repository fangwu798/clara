package com.clara.adapter.rest;

import com.clara.kernel.exception.ClaraError;

/**
 * 封装REST操作的响应数据。
 */
public final class RestResponse<T> {
    private final int status;
    private final T body;
    private final ClaraError error;

    private RestResponse(int status, T body, ClaraError error) {
        this.status = status;
        this.body = body;
        this.error = error;
    }

    public static <T> RestResponse<T> ok(int status, T body) {
        return new RestResponse<T>(status, body, null);
    }

    public static <T> RestResponse<T> error(int status, ClaraError error) {
        return new RestResponse<T>(status, null, error);
    }

    public int getStatus() {
        return status;
    }

    public T getBody() {
        return body;
    }

    public ClaraError getError() {
        return error;
    }
}
