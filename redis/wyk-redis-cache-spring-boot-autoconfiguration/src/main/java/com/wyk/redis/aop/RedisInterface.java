package com.wyk.redis.aop;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RedisInterface {
    String value() default "defaultValue"; //key前缀
    String key(); //SpEL表达式
    String defaultVal() default "defaultVal"; //KEY为null的默认值
    RedisModel redisModel() default RedisModel.QUERY; //方法的模式
    String handler() default "exception";
    String bloomKey() default ""; //布隆过滤器key
}
