package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * @author cgJavaAfter
 * @date 2023-03-06 16:45
 */
public class RedisLock implements ILock{

    private StringRedisTemplate template;
    private String name;

    private static final String UUID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final String KEY_PREFIX = "lock:";

    //通过构造器为key的name字段和template赋值
    public RedisLock( String name,StringRedisTemplate template) {
        this.template = template;
        this.name = name;
    }

    //获取锁
    @Override
    public boolean tryLock(long timeOut) {
        //获取线程标识，让每个线程获取的锁的value都不同；    优化：使用UUID+线程号区分不同分布式服务器和用户
        String threadId = UUID_PREFIX + Thread.currentThread().getId();
        //获取锁，设置超时时间和不能额外添加
        Boolean ifAbsent = template.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeOut, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ifAbsent);//防止自动装箱空指针
    }

    //释放锁
    @Override
    public void unLock() {
        //优化1：在删除前先获取线程标识判断是否与自己一致，防止因为故障导致暂停时间大于TTL导致的锁被回收
        String threadId = UUID_PREFIX + Thread.currentThread().getId();
        //1.2获取锁的id标识
        String redisId = template.opsForValue().get(KEY_PREFIX + name);
        if(threadId.equals(redisId)){
            //相同即释放锁
            template.delete(KEY_PREFIX + name);
        }
    }
}
