# WYK Redis AOP Cache
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://www.java.com/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7+-green.svg)](https://spring.io/projects/spring-boot)

基于 Spring AOP 的 Redis 缓存增强组件, 支持:
- 提供了两个注解RedisInterface与RedisCache,RedisCache为实验版本
- 缓存自动查询/更新/删除
- 布隆过滤器防止缓存穿透
- 分布式锁/本地锁防止缓存击穿
- 空值降级策略
- 支持事务提交后再写缓存
- 动态热点检测升级和降级
# 功能
- **查询缓存**: 自动读取 Redis，如果命中直接返回，未命中执行方法并写入缓存
- **更新/删除缓存**: 方法执行后自动删除或更新缓存
- **布隆过滤器**: 防止缓存穿透
- **本地锁/分布式锁**: 防止高并发下缓存击穿, 提供两个默认锁实现
- **空值降级处理**: 支持自定义策略处理空缓存
- **热点检测**: 支持自动检测高频访问key并升级
# 快速开始
- 导入到本地maven仓库:
```bash
  git clone https://github.com/kel319/wyk
  cd wyk/redis/wyk-redis-cache-spring-boot-autoconfiguration
  mvn clean install
  cd ..
  cd wyk-redis-cache-spring-boot-starter
  mvn clean install
```
- 引入依赖:
```xml
	<dependency>
			<groupId>com.wyk</groupId>
			<artifactId>wyk-redis-cache-spring-boot-starter</artifactId>
			<version>1.0-SNAPSHOT</version>
	</dependency>
```
- application.yml配置:
```yml
  wyk:
  redis:
    cache:
      enable: true # RedisInterface开关,true即可用RedisInterface,必填
```
- 注解使用
```java
  @RedisInterface(
    key = "#id", # spel表达式,必填
  )
  public User getUserById(Long id) {
    // 数据库查询逻辑
  }
```
# 配置
- 配置详解

| 配置项                 | 说明                           | 默认值          |
| ---------------------- | --------------------------- | ------------ |
| enable                 | RedisInterface 开关，true 即可使用 | —            |
| test                   | RedisCache 开关               | false        |
| cluster                | 集群模式开关                      | true         |
| bloom                  | 布隆过滤器开关                     | true        |
| nil                    | 空值缓存开关                      | true         |
| watchdog               | 分布式锁自动续期开关                  | true         |
| nilValue               | 空值占位符                       | "__NULL__"   |
| strategy               | 空值降级策略后缀                    | Handler      |
| maxExpires             | 最大缓存随机时间（秒）                 | 31           |
| minExpires             | 最小缓存随机时间（秒）                 | 10           |
| localLockTimeOut       | 本地锁超时时间（秒）                  | 2            |
| distributedLockTimeOut | 分布式锁过期时间（秒）                 | 30           |
| lock                   | RedisCache 注解锁策略选择          | defaultRedis |
| expectedSize           | 布隆过滤器预期插入条数                 | 10000        |
| interval           	 | 热点时间间隔                       | 3600        |
| threshold              | 热点访问频率阈值                  | 200        |
| hotspotEnable          | 热点检测开关                      | true        |
- 注解详解

| 参数名        | 说明                                | 默认值              |
| ---------- | --------------------------------- | ---------------- |
| key        | SpEL 表达式，用于生成缓存 Key，必填            | —                |
| value      | 缓存 Key 前缀                         | defaultValue     |
| defaultVal | SpEL 解析结果为 null 时使用的 Key          | defaultVal       |
| redisModel | 缓存方法模式，支持 QUERY / UPDATE / DELETE | RedisModel.QUERY |
| handler    | 降级策略处理器名称，自定义 Bean 名              | ExceptionHandler |
| bloomKey   | 布隆过滤器 Key，空表示不启用                  | ""               |
- 空值降级策略(RedisInterface与RedisCache共用):
```java
  public interface CacheMissHandler {
    Object handle(String key, JavaType type);
  }
```
- 实现CacheMissHandler接口重写handle方法,并注册为SpringBean
```java
  public class CustomizeExceptionHandler implements CacheMissHandler {
    @Override
    public Object handle(String key, JavaType type) {
        throw new CustomizeException("查询失败,数据不存在: " + key, Status.BAD_REQUEST.getCode());
    }
  }
  @RedisInterface(
    key = "#id", # 必填
    handler = "customizeException", # 去除配置文件参数strategy指定后缀后，首字母小写
  )
```
- 锁策略(RedisCache可用)
```java
  public interface CacheLock {
    void tryLock(String key, String value) throws InterruptedException;
    void unLock(String key, String value);
    default Object executeWithLock(ProceedingJoinPoint joinPoint, String key, RedisUtil redisUtil) throws Throwable {
      //模板方法
    }
  }
```
- 实现CacheLock接口,重写tryLock和unLock方法,也可以重写模板方法
```java
  public class CustomizeLock implements CacheLock {
    @Override
    public void tryLock(String key, String value) {
      //获取锁
    }
    @Override
    public void unLock(String key, String value) {
      //释放锁
    }
  }
```
- 在配置文件中填写
```java
  wyk.redis.cache.lock: customize # 使用实现类名首字母小写,如果以Lock后缀需要去除后缀
```
- 提供2个默认锁实现defaultRedis与defaultLocalReentrant
## 注意事项
- @RedisInterface和@RedisCache是一样的,只是后者能扩展锁策略和热点升级功能,前者通过cluster开关自由选择两种锁
- 默认值可以不配置,可以直接引入依赖后配置
