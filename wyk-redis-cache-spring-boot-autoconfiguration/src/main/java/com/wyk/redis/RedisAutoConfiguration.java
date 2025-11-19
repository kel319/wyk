package com.wyk.redis;



import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyk.redis.aop.RedisAop;
import com.wyk.redis.cache.CacheMissHandler;
import com.wyk.redis.cache.imp.EmptyHandler;
import com.wyk.redis.cache.imp.ExceptionHandler;
import com.wyk.redis.util.BloomFilter;
import com.wyk.redis.util.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

@AutoConfiguration
@EnableConfigurationProperties(value = RedisProperties.class)
//@ConditionalOnProperty(prefix = "wyk.redis.cache",name = "enable",havingValue = "true")
@ConditionalOnExpression("${wyk.redis.cache.enable:false} == true or ${wyk.redis.cache.test:false} == true")
public class RedisAutoConfiguration {


    private static final Logger log = LoggerFactory.getLogger(RedisAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(BloomFilter.class)
    @ConditionalOnProperty(prefix = "wyk.redis.cache",name = "bloom",havingValue = "true",matchIfMissing = true)
    public BloomFilter bloomFilter(RedisProperties redisProperties){
        log.info("=== 创建 BloomFilter Bean ===");
        Integer expectedSize = redisProperties.getExpectedSize();
        double m =  (- expectedSize * Math.log(0.01))/(Math.log(2) * Math.log(2));
        int bitArraySize = (int) Math.ceil(m);
        return new BloomFilter(new AtomicLongArray( (bitArraySize+63)/64), bitArraySize,5);
    }

    @Bean
    @ConditionalOnMissingBean(RedisUtil.class)
    public RedisUtil redisUtil(
            RedisTemplate<String,Object> redisTemplate,
            ObjectMapper objectMapper,
            RedisProperties redisProperties
    ) {
        log.info("=== 创建 RedisUtil Bean ===");
        return new RedisUtil(
                redisTemplate,
                objectMapper,
                redisProperties.getNilTime(),
                redisProperties.getMaxExpires(),
                redisProperties.getMinExpires(),
                redisProperties.getNilValue(),
                redisProperties.isWatchdog()
        );
    }

    @Bean
    @ConditionalOnMissingBean(name = "cacheMissHandlerMap")
    public Map<String,CacheMissHandler> cacheMissHandlerMap(ObjectProvider<List<CacheMissHandler>> provider,
                                                            RedisProperties redisProperties) {
        List<CacheMissHandler> cacheMissHandlers = provider.getIfAvailable(ArrayList::new);
        HashMap<String, CacheMissHandler> map = new HashMap<>();
        map.put("exception",new ExceptionHandler());
        map.put("empty",new EmptyHandler());
        cacheMissHandlers.stream()
                .filter(handler -> !(handler instanceof EmptyHandler || handler instanceof ExceptionHandler))
                .forEach(handler -> {
                    String strategy = redisProperties.getStrategy() == null ? "Handler" : redisProperties.getStrategy();
                    String handlerName = generateHandlerName(handler, strategy);
                    map.put(handlerName,handler);
                    log.info("策略: {} 注入成功",handlerName);
                });
        return map;
    }

    @Bean
    @ConditionalOnMissingBean(RedisAop.class)
    @ConditionalOnProperty(prefix = "wyk.redis.cache",name = "enable",havingValue = "true")
    public RedisAop redisAop(RedisProperties redisProperties,
                             RedisUtil redisUtil,
                             Map<String,CacheMissHandler> cacheMissHandlerMap,
                             @Autowired(required = false) BloomFilter bloomFilter) {
        log.info("=== 创建 RedisAop Bean ===");
        return new RedisAop(
                redisUtil,
                cacheMissHandlerMap,
                bloomFilter,
                redisProperties.isCluster(),
                redisProperties.isBloom(),
                redisProperties.isNil(),
                redisProperties.getNilValue(),
                redisProperties.getLocalLockTimeOut(),
                redisProperties.getDistributedLockTimeOut()
        );
    }
    private String generateHandlerName(CacheMissHandler cacheMissHandler,String strategy) {
        String simpleName = cacheMissHandler.getClass().getSimpleName().trim();
        if (simpleName.endsWith(strategy)) {
            String temp = simpleName.substring(0,simpleName.length()-strategy.length());
            return temp.substring(0,1).toLowerCase() + temp.substring(1);
        }
        return simpleName.substring(0,1).toLowerCase() + simpleName.substring(1);
    }
}
