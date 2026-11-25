package com.onebuild.common;

public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final String code;
    private final transient Object data;

    public ApiException(int httpStatus, String code, String message) {
        this(httpStatus, code, message, null);
    }

    public ApiException(int httpStatus, String code, String message, Object data) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.data = data;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "BAD_REQUEST", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "UNAUTHORIZED", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException conflict(String code, String message, Object data) {
        return new ApiException(409, code, message, data);
    }
}
