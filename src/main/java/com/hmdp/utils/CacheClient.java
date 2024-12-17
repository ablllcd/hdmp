package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, long expire, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),expire,timeUnit);
    }

    public <R,I> R getWithPassThrough(String key, Class<R> resultType, I dbID, Function<I,R> dbQueryFunction,
                                      long expire, TimeUnit timeUnit){
        // 1.从redis缓存中查找
        String valueJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(valueJson)) {
            // 2.如果缓存中有则直接返回
            System.out.println(key + "缓存命中");
            R value = JSONUtil.toBean(valueJson, resultType);
            return value;
        }
        // 2.1 如果缓存中是空值，也进行返回
        if(valueJson != null){
            return null;
        }
        // 3.缓存未命中则从数据库查找
        R dbValue = dbQueryFunction.apply(dbID);
        // 4.如果数据库未命中则返回null
        if (dbValue == null){
            // 4.1 缓存空值，避免缓存穿透
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5.数据库找到则放入缓存中并设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(dbValue),expire, timeUnit);

        // 6.返回value
        return dbValue;
    }
}
