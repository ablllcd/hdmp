package com.hmdp.utils;

import cn.hutool.core.date.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class IDGenerator {
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    public long nextID(String keyType){
        // 1. 计算时间戳，默认不会超过31 bit (以天为单位）
        LocalDate startDate = LocalDate.of(2024, 12, 1);
        LocalDate nowDate = LocalDate.now();
        Period between = Period.between(startDate, nowDate);
        long timeStamp = (long) between.getDays();

        // 2. 为一天内的ID创建序列号，默认不会超过32 bit
        long serial = stringRedisTemplate.opsForValue().increment("global:id:" + keyType + timeStamp);

        // 3. 拼接时间戳和序列化
        long id = timeStamp<<32 | serial;

        return id;
    }
}
