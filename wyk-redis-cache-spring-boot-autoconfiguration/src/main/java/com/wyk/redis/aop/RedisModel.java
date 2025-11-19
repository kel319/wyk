package com.wyk.redis.aop;


public enum RedisModel {
    QUERY("query"),
    UPDATE("update"),
    INSERT("insert"),
    DELETE("delete");

    private final String name;

    public String getName() {
        return name;
    }

    RedisModel(String name) {
        this.name = name;
    }
}
