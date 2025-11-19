package com.wyk.redis.cache;

public enum Status {
    CREATED(201),
    NO_CONTENT(204),

    OK(200),
    BAD_REQUEST(400),

    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    CONFLICT(409),
    INTERNAL_SERVER_ERROR(500),
    BAD_GATEWAY(501),
    SERVICE_UNAVAILABLE(503);

    private final Integer code;

    Status(Integer code) {
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
