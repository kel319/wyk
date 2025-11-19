package com.wyk.redis.util;


import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;


/*
  redis工具类，提供标准redis方法
 */
public class RedisUtil {

    private static final ScheduledExecutorService schedule = Executors.newScheduledThreadPool(1);

    private static final long DEFAULT_TIME = 60;
    private final RedisTemplate<String,Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(RedisUtil.class);
    private final Long nilTime; //单位s
    private final String nilValue;
    private final boolean watchdog; //仅cluster = true有效
    private final Long max_expires;
    private final Long min_expires;
    private final Map<String,CompletableFuture<Void>> completableFutureMap = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduleExecutor = Executors.newScheduledThreadPool(1);
    public RedisUtil(RedisTemplate<String, Object> redisTemplate,
                     ObjectMapper objectMapper,
                     Long nilTime,
                     Long max_expires,
                     Long min_expires,
                     String nilValue,
                     boolean watchdog) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.nilTime = nilTime;
        this.nilValue = nilValue;
        this.watchdog = watchdog;
        this.max_expires = max_expires;
        this.min_expires = min_expires;
    }

    // 非泛型类redis获取
    public <T> T get(String key,Class<T> tClass) {
        try {
            return Optional.ofNullable(key)
                    .map(k -> redisTemplate.opsForValue().get(k))
                    .filter(tClass::isInstance)
                    .map(tClass::cast)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("redis获取失败,key: {},tClass: {},error: {}",key,tClass,e.getMessage());
            log.debug("redis获取失败,key: {},tClass: {},error: ",key,tClass,e);
            return null;
        }
    }

    // 泛型类redis获取
    public <T> T get(String key, JavaType javaType) {
        try {
            Object object = redisTemplate.opsForValue().get(key);
            if (object == null) return null;
            return objectMapper.convertValue(object,javaType);
        } catch (IllegalArgumentException e) {
            log.warn("类型转换异常,key: {},javaType: {},error: {}",key,javaType,e.getMessage());
            log.debug("类型转换异常,key: {},javaType: {},error: ",key,javaType,e);
            return null;
        } catch (Exception e) {
            log.warn("redis获取失败,key: {},javaType: {},error: {}",key,javaType,e.getMessage());
            log.debug("redis获取失败,key: {},javaType: {},error: ",key,javaType,e);
            return null;
        }
    }

    public Object get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("redis获取失败,key: {},error: {}",key,e.getMessage());
            log.debug("redis获取失败,key: {},error: ",key,e);
            return null;
        }
    }

    //设置redis值(不带过期时间)
    public <T> void set(String key,T value) {
        if (value != null && key != null) {
            try {
                redisTemplate.opsForValue().set(key,value);
            } catch (Exception e) {
                log.warn("设置redis失败,key: {},value: {},error: {}",key,value,e.getMessage());
                log.debug("设置redis失败,key: {},value: {},error: ",key,value,e);
            }
        }
    }
    //设置redis值(带过期时间)
    public <T> void set(String key,T value,long time) {
        if (value != null && key != null) {
            if (time > 0)
                try {
                    redisTemplate.opsForValue().set(key,value,time, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("设置带过期时间redis失败,key: {},value: {},time: {},error: {}",key,value,time,e.getMessage());
                    log.warn("设置带过期时间redis失败,key: {},value: {},time: {},error: ",key,value,time,e);
                }
            else try {
                redisTemplate.opsForValue().set(key,value);
            } catch (Exception e) {
                log.warn("设置带无效过期时间redis失败,key: {},value: {},time: {},error: {}",key,value,time,e.getMessage());
                log.warn("设置带无效过期时间redis失败,key: {},value: {},time: {},error: ",key,value,time,e);
            }
        }
    }
    //设置redis值(默认时间60分钟)
    public <T> void setDefault(String key,T value) {
        if (value != null && key != null) {
            try {
                redisTemplate.opsForValue().set(key,value,DEFAULT_TIME,TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("设置默认过期redis失败,key: {},value: {},error: {}",key,value,e.getMessage());
                log.warn("设置默认过期redis失败,key: {},value: {},error: ",key,value,e);
            }
        }
    }

    //设置随机过期时间redis值
    public <T> void setRandomExpires(String key,T value) {
        try {
            if (max_expires > min_expires) {
                redisTemplate.opsForValue().set(key,value,ThreadLocalRandom.current()
                        .nextLong(min_expires,max_expires),TimeUnit.SECONDS);
            } else redisTemplate.opsForValue().set(key,value,ThreadLocalRandom.current()
                    .nextLong(10,31),TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("设置随机过期redis失败,key: {},value: {},error: {}",key,value,e.getMessage());
            log.warn("设置随机过期redis失败,key: {},value: {},error: ",key,value,e);
        }
    }


    //设置redis值(空值防穿透过期时间30s)
    public void set(String key) {
        if (key != null) {
            try {
                redisTemplate.opsForValue().set(key,nilValue,nilTime,TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("redis设置NULL值失败,key: {},error: {}",key,e.getMessage());
                log.debug("redis设置NULL值失败,key: {},error: ",key,e);
            }
        }
    }



    //删除指定redis缓存
    public void remove(String key) {
        if (key != null) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("删除指定redis失败,key: {},error: {}",key,e.getMessage());
                log.debug("删除指定redis失败,key: {},error: ",key,e);
            }
        }
    }

    //热点key升级
    public void upgrade(String key) {
        if (key != null) {
            try {
                redisTemplate.persist(key);
            } catch (Exception e) {
                log.warn("热点key升级失败,key: {},error: {}",key,e.getMessage());
                log.debug("热点key升级失败,key: {},error: ",key,e);
            }
        }
    }

    //热点key降级
    public void downgrade(String key) {
        if (key != null) {
            try {
                if (max_expires > min_expires)
                    redisTemplate.expire(key,ThreadLocalRandom.current().nextLong(min_expires,max_expires), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("热点key降级失败,key: {},error: {}",key,e.getMessage());
                log.debug("热点key降级失败,key: {},error: ",key,e);
            }
        }
    }

    public boolean setDistributedLock(String key,String value,Long timeSeconds) {
        try {
            removeOldFuture(key);
            Boolean lock = redisTemplate.opsForValue().setIfAbsent("DistributedLock"+key, value, Duration.ofSeconds(timeSeconds));
            if (Boolean.TRUE.equals(lock) && watchdog) {
                log.info("获取锁成功");
                CompletableFuture<Void> future = new CompletableFuture<>();
                ScheduledFuture<?> scheduled = schedule.scheduleAtFixedRate(() -> {
                    Object newKey = redisTemplate.opsForValue().get("DistributedLock"+key);
                    if (value.equals(newKey)) {
                        redisTemplate.expire("DistributedLock"+key, Duration.ofSeconds(timeSeconds));
                        log.debug("锁自动续期");
                    } else {
                        future.complete(null);
                    }
                }, timeSeconds / 3, timeSeconds / 3, TimeUnit.SECONDS);
                future.thenRun(() -> {
                    scheduled.cancel(false);
                    completableFutureMap.remove(key,future);
                    log.debug("自动续期取消");
                });
                completableFutureMap.put(key,future);
            }
            return Boolean.TRUE.equals(lock);
        } catch (Exception e) {
            log.warn("获取分布式锁 {}:{} 失败: {}",key,value,e.getMessage());
            log.debug("获取分布式锁 {}:{} 失败",key,value,e);
        }
        return false;
    }
    public boolean delDistributedLock(String key,String value) {
        String luaScript = """
                if redis.call('GET',KEYS[1]) == ARGV[1] then
                    redis.call('DEL',KEYS[1])
                    return 1
                end
                return 0
                """;
        RedisScript<Long> script = RedisScript.of(luaScript,Long.class);
        try {
            Long execute = redisTemplate.execute(script, List.of("DistributedLock"+key), value);
            return execute > 0;
        } catch (Exception e) {
            log.warn("释放分布式锁 {}:{} 失败: {}",key,value,e.getMessage());
            log.debug("释放分布式锁 {}:{} 失败",key,value,e);
        }

        return false;
    }

    public boolean deductInv(List<String> keys, Integer num) {
        String luaScript = """
                local stock = tonumber(redis.call('GET',KEYS[1]) or '0')
                if stock == -1 then
                    return -1
                end
                local num = tonumber(ARGV[1] or '0')
                if num == nil or num <= 0 then
                    return 0
                end
                if stock >= num then
                    redis.call('DECRBY',KEYS[1],num)
                    redis.call('INCRBY',KEYS[2],num)
                    return 1
                else
                    return 0
                end
                """;
        RedisScript<Integer> longRedisScript = RedisScript.of(luaScript, Integer.class);
        Integer result = redisTemplate.execute(longRedisScript, keys, num.toString());
        return switch (result) {
            case 1,-1 -> true;
            default -> false;
        };
    }

    public boolean addInv(List<String> keys, Integer num) {
        String luaScript = """
                local cache = tonumber(redis.call('GET',KEYS[2]) or '0')
                local num = tonumber(ARGV[1] or '0')
                local stock = tonumber(redis.call('GET',KEYS[1]) or '0')
                if num == nil or num <= 0 or stock == -1 then
                    return 0
                end
                if cache >= num then
                    redis.call('DECRBY',KEYS[2],num)
                    redis.call('INCRBY',KEYS[1],num)
                    return 1
                end
                return 0
                """;
        RedisScript<Integer> integerRedisScript = RedisScript.of(luaScript, Integer.class);
        Integer result = redisTemplate.execute(integerRedisScript, keys, num.toString());
        return result.equals(1);
    }

    public boolean deductInv(List<String> keys, List<Integer> nums) {
        String luaScript = """
                for i=1,#KEYS,2 do
                    local stock = tonumber(redis.call('GET',KEYS[i])) or 0
                    local argIndex = math.floor((i+1)/2)
                    local num = tonumber(ARGV[argIndex]) or 0
                    if stock < num and stock ~= -1 then
                        return 0
                    end
                end
                for i=1,#KEYS,2 do
                    local argIndex = math.floor((i+1)/2)
                    local num = tonumber(ARGV[argIndex]) or 0
                    local stock = tonumber(redis.call('GET',KEYS[i])) or 0
                    if stock ~= -1 then
                        redis.call('DECRBY',KEYS[i],num)
                        redis.call('INCRBY',KEYS[i+1],num)
                    end
                end
                return 1
                """;
        RedisScript<Long> longRedisScript = RedisScript.of(luaScript, Long.class);
        String[] args = nums.stream()
                .map(String::valueOf)
                .toArray(String[]::new);
        Long result = redisTemplate.execute(longRedisScript, keys,(Object[]) args);
        return result.equals(1L);
    }
    public boolean addInv(List<String> keys, List<Integer> nums) {
        String luaScript = """
                for i = 1,#KEYS,2 do
                    local stock = tonumber(redis.call('GET',KEYS[i])) or 0
                    local cache = tonumber(redis.call('GET',KEYS[i+1])) or 0
                    local argIndex = math.floor((i+1)/2)
                    local num = tonumber(ARGV[argIndex]) or 0
                    if cache < num and stock ~= -1 then
                        return 0
                    end
                end
                for i=1,#KEYS,2 do
                    local stock = tonumber(redis.call('GET',KEYS[i])) or 0
                    local argIndex = math.floor((i+1)/2)
                    local num = tonumber(ARGV[argIndex]) or 0
                    if stock ~= -1 and num ~= 0 then
                        redis.call('DECRBY',KEYS[i+1],num)
                        redis.call('INCRBY',KEYS[i],num)
                    end
                end
                return 1
                """;
        RedisScript<Long> longRedisScript = RedisScript.of(luaScript, Long.class);
        String[] args = nums.stream()
                .map(String::valueOf)
                .toArray(String[]::new);
        Long result = redisTemplate.execute(longRedisScript, keys,(Object[]) args);
        return result.equals(1L);
    }
    //支付成功后的删锁定库存
    public boolean paySuccess(List<String> keys, List<Integer> args) {
        String luaScript = """
                for i = 1,#KEYS,1 do
                    local cache = tonumber(redis.call('GET',KEYS[i]) or '0')
                    local num = tonumber(ARGV[i] or '0')
                    if cache < num then
                        return 0
                    end
                end
                for i = 1,#KEYS,1 do
                    local cache = tonumber(redis.call('GET',KEYS[i]) or '0')
                    local num = tonumber(ARGV[i] or '0')
                    if cache >= num and num ~= 0 then
                        redis.call('DECRBY',KEYS[i],num)
                    end
                end
                return 1
                """;
        RedisScript<Integer> script = RedisScript.of(luaScript, Integer.class);
        Integer result = redisTemplate.execute(script, keys,
                (Object[]) args.stream().map(String::valueOf).toArray(String[]::new));
        return result.equals(1);
    }

    private void removeOldFuture(String key) {
        CompletableFuture<Void> future = completableFutureMap.remove(key);
        if (future != null && !future.isDone()) {
            future.complete(null);
            log.debug("手动取消看门狗任务: {}", key);
        }
    }

}
