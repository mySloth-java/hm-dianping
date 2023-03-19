package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author cgJavaAfter
 * @date 2023-03-05 15:38
 */
//基于redis的id生成器
@Component
public class RedisIdWork {
    private static final long BEGIN_TIMESTAMP = 1640995200L;//初始时间戳(20220101)

    private static final int COUNT_BITS = 32;//序列号位数

    @Resource
    private StringRedisTemplate template;

    //自增Id功能实现
    public long nextId(String keyPrefix){
        //1、生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);//转化为时间戳
        long timestamp =  second - BEGIN_TIMESTAMP;

        //2、生成序列号
        //2.1获取当前日期，精确到天，每天都对应不同的序列号
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long increment = template.opsForValue().increment("icr:" + keyPrefix + ":" + data);

        //3.对时间戳和序列号进行拼接，利用位运算
        //总共有64位存储，前31存储时间戳，后32存储序列号
        return timestamp << COUNT_BITS | increment;
    }

}
