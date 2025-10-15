package com.wyk.redis.cache;

public enum Status {
    BAD_REQUEST(400),
    INTERNAL_SERVER_ERROR(500);


    private final Integer code;

    Status(Integer code) {
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
