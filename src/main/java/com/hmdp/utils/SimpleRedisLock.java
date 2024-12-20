package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOCK_PREFIX;

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    private String keyName;
    private Long uniqueNum;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String keyName){
        this.stringRedisTemplate = stringRedisTemplate;
        this.keyName = keyName;
        uniqueNum = stringRedisTemplate.opsForValue().increment("thread-prefix", 1);
    }

    @Override
    public boolean tryLock(long timeSecs) {
        long threadID = Thread.currentThread().getId();

        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_PREFIX + keyName, threadID + "-"+uniqueNum, timeSecs, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(locked);
    }

    @Override
    public void unlock() {
        // 1. 获取当前线程ID
        long threadID = Thread.currentThread().getId();
        // 1.1 threadID拼接uniqueNum避免多台设备使用相同的threadID
        String combinedThreadID = threadID + "-" + uniqueNum;
        // 2. 获取redis中的线程ID
        String redisVale = stringRedisTemplate.opsForValue().get(LOCK_PREFIX + keyName);
        // 3.如果两者ID相同才进行删除，避免误删
        if (combinedThreadID.equals(redisVale)) {
            stringRedisTemplate.delete(LOCK_PREFIX + keyName);
        }
    }

//    @Override
//    public void unlock() {
//        // 调用lua脚本执行多个redis操作，并且保证这些操作被原子执行
//        // 1. 加载Lua脚本
//        DefaultRedisScript<Long> unlockScript = new DefaultRedisScript<>();
//        unlockScript.setLocation(new ClassPathResource("unlock.lua"));
//        unlockScript.setResultType(Long.class);
//        // 2. 执行lua脚本
//        long threadID = Thread.currentThread().getId();
//        String combinedThreadID = threadID + "-" + uniqueNum;
//        stringRedisTemplate.execute(unlockScript, List.of(LOCK_PREFIX + keyName), combinedThreadID);
//    }

}
