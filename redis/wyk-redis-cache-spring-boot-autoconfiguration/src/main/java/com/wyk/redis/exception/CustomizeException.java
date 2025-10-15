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

    public CustomizeException(String message,Integer code) {
        super(message);
        this.code = code;
    }
    public CustomizeException(String message) {
        super(message);
        this.code = Status.BAD_REQUEST.getCode();
    }
}
