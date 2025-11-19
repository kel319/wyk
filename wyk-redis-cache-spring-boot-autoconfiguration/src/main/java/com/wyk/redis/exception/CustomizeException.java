package com.wyk.redis.exception;

import com.wyk.redis.cache.Status;

import java.io.Serial;

public class CustomizeException extends RuntimeException{
    @Serial
    private static final long serialVersionUID = 1L;

    private final Integer code;

    public Integer getCode() {
        return code;
    }

    public CustomizeException(String message,Integer code,Throwable cause) {
        super(message,cause);
        this.code = code;
    }

    public CustomizeException(String message,Integer code) {
        super(message);
        this.code = code;
    }
    public CustomizeException(String message) {
        super(message);
        this.code = Status.BAD_REQUEST.getCode();
    }
    public static CustomizeException badRequest(String message,Throwable cause) {
        return new CustomizeException(message,Status.BAD_REQUEST.getCode(),cause);
    }
    public static CustomizeException badRequest(String message) {
        return new CustomizeException(message);
    }
    public static CustomizeException internalServerError(String message,Throwable cause) {
        return new CustomizeException(message,Status.INTERNAL_SERVER_ERROR.getCode(),cause);
    }
    public static CustomizeException internalServerError(String message) {
        return new CustomizeException(message,Status.INTERNAL_SERVER_ERROR.getCode());
    }
    public static CustomizeException conflict(String message, Throwable cause) {
        return new CustomizeException(message,Status.CONFLICT.getCode(),cause);
    }
    public static CustomizeException conflict(String message) {
        return new CustomizeException(message,Status.CONFLICT.getCode());
    }
    public static CustomizeException notFound(String message, Throwable cause) {
        return new CustomizeException(message,Status.NOT_FOUND.getCode(),cause);
    }
    public static CustomizeException notFound(String message) {
        return new CustomizeException(message,Status.NOT_FOUND.getCode());
    }
}
