package com.wyk.redis.exception;

import com.wyk.redis.cache.Status;
import lombok.Getter;

/**
 * Redis 缓存 AOP 用异常，带状态码。
 */
@Getter
public class RedisCacheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Integer code;

    public RedisCacheException(String message, Integer code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public RedisCacheException(String message, Integer code) {
        super(message);
        this.code = code;
    }

    public RedisCacheException(String message) {
        super(message);
        this.code = Status.BAD_REQUEST.getCode();
    }

    public static RedisCacheException badRequest(String message, Throwable cause) {
        return new RedisCacheException(message, Status.BAD_REQUEST.getCode(), cause);
    }

    public static RedisCacheException badRequest(String message) {
        return new RedisCacheException(message);
    }

    public static RedisCacheException internalServerError(String message, Throwable cause) {
        return new RedisCacheException(message, Status.INTERNAL_SERVER_ERROR.getCode(), cause);
    }

    public static RedisCacheException internalServerError(String message) {
        return new RedisCacheException(message, Status.INTERNAL_SERVER_ERROR.getCode());
    }

    public static RedisCacheException conflict(String message, Throwable cause) {
        return new RedisCacheException(message, Status.CONFLICT.getCode(), cause);
    }

    public static RedisCacheException conflict(String message) {
        return new RedisCacheException(message, Status.CONFLICT.getCode());
    }

    public static RedisCacheException notFound(String message, Throwable cause) {
        return new RedisCacheException(message, Status.NOT_FOUND.getCode(), cause);
    }

    public static RedisCacheException notFound(String message) {
        return new RedisCacheException(message, Status.NOT_FOUND.getCode());
    }
}
