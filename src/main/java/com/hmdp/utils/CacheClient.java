package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * @author cgJavaAfter
 * @date 2023-03-05 13:14
 */

@Slf4j
@Component
//基于stringRedisTemplate封装的缓存工具类
public class CacheClient {
    @Resource
    private StringRedisTemplate template;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //将任意java对象序列化json存储在redis中，设置TTL时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        template.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //在上基础上修改了TTL，改为逻辑过期时间，处理缓存击穿
    public void setWithExpire(String key,Object value,Long time,TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //不确定传入时间的单位，统一转换秒存储
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        template.opsForValue().set(key,JSONUtil.toJsonStr(value));
    }

    //根据指定的key查询缓存，反序列化为指定类型，缓存空值解决缓存穿透
    //参数分别是key前缀和id，反序列化的类型，函数式接口，用来调用数据库，以及TTL的时间设置R代表返回类型
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbBack,Long time, TimeUnit unit){
        //1、查询redis
        String key = keyPrefix + id;
        String shopJson = template.opsForValue().get(key);
        //2、判断redis是否有其内容，有就返回，没有就对数据库进行查询
        if(StrUtil.isNotBlank(shopJson)){
            //有内容，返回结果，把json反序列化为指定类型
            return JSONUtil.toBean(shopJson,type);
        }

        //3.1缓存穿透后续判断，存入""到数据库中，不让其再访问数据库；shopJson前面已经做了判断，只有null和""
        if(shopJson != null){
            return null;
        }
        //3、对数据库查询，不存在就报错，由于类型不确定所以对于数据库查询的具体功能需要外面组为函数传递进来
        R r = dbBack.apply(id);
        if(r == null){
            //3.1添加新功能，防止缓存穿透
            template.opsForValue().set(key,"",2,TimeUnit.MINUTES);
            return null;
        }
        //4、数据库存在就存入redis缓存中提高下一次查询效率
        //4.1添加新功能：缓存更新策略；即当后端更改数据时要保证数据库和redis缓存之间的一致性
        //将对象再次转化为json字符串存储

        //调用上面的set方法写入redis
        set(key,r,time,unit);
        return r;
    }

    //根据指定key查询缓存，反序列化指定类型，利用逻辑过期解决缓存击穿问题
    public <R,ID> R queryWithExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbBack,Long time, TimeUnit unit){
        //1、从redis缓存中查询看能不能查询到
        String key = keyPrefix + id;
        String shopJson = template.opsForValue().get(key);
        //2、判断redis是否有其内容，不存在直接返回
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //3、存在把json字符串反序列化对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4、判断是否过期，比较时间戳
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，返回缓存中的数据
            return r;
        }
        //5、过期对缓存进行重建
        String lockKey = LOCK_SHOP_KEY + id;
        //5.1获取互斥锁
        boolean flag = tryLock(lockKey);
        //5.2判断是否获取成功
        if(flag){
            //获取锁成功后应再次检测缓存是否过期，存在无需重建
            //获取成功开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //查询数据库
                R apply = dbBack.apply(id);
                //添加redis缓存
                setWithExpire(key,JSONUtil.toJsonStr(apply),time,unit);
            });
        }
        //5.3未获取成功则代表已经有线程在更新数据，先返回过期信息
        return r;
    }

    //自定义开启锁方法
    private boolean tryLock(String key){
        Boolean flag = template.opsForValue().setIfAbsent(key, "1", 15, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//防止自动拆箱出现空指针
    }
    //自定义解锁方法
    private void unLock(String key){
        template.delete(key);
    }

}
