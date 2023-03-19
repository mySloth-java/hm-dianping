package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate template;

    @Resource
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
//        Shop shop = queryWithExpire(id);
//        if(shop == null){
//            return Result.fail("店铺不存在");
//        }

        //调用工具类解决缓存穿透，lambda表达式还可简写为this::getById
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,shopId ->getById(shopId),
                CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //调用工具类解决缓存击穿
//        Shop shop = cacheClient.queryWithExpire(CACHE_SHOP_KEY,id,Shop.class,shopId ->getById(shopId),
//                CACHE_SHOP_TTL,TimeUnit.MINUTES);

        return Result.ok(shop);
    }
    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        //1、从redis缓存中查询看能不能查询到
        String shopJson = template.opsForValue().get(CACHE_SHOP_KEY + id);
        //2、判断redis是否有其内容，有就返回，没有就对数据库进行查询
        if(StrUtil.isNotBlank(shopJson)){
            //将json转为对象
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            Shop shop = JSON.parseObject(shopJson,Shop.class);
//            return Result.ok(shop);
            return shop;
        }

        //3.1缓存穿透后续判断，存入""到数据库中，不让其再访问数据库；shopJson前面已经做了判断，只有null和""
        if(shopJson != null){
//            return Result.fail("店铺不存在");
            return null;
        }

        //4、实现互斥锁的缓存重建
        //4.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean flag = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!flag){
                //获取锁失败代表有其他线程在重建key，每过多少时间重现查询一次，直到重建完成
                Thread.sleep(50);
                return queryWithMutex(id);//递归
            }
            //4.3成功根据id查询数据库
            shop = getById(id);
            //模拟重建延迟
            Thread.sleep(500);

            //5、对数据库查询，不存在就报错
            if(shop == null){
                //5.1添加新功能，防止缓存穿透
                template.opsForValue().set(lockKey,"",2,TimeUnit.MINUTES);
    //            return Result.fail("店铺不存在");
                return null;
            }
            //6、存在就写入redis缓存
            template.opsForValue().set(CACHE_SHOP_KEY+id,JSON.toJSONString(shop),
                    RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);//设置缓存有效期

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7、释放锁
            unLock(lockKey);
        }
        return shop;
    }

    //逻辑过期解决缓存击穿
    public Shop queryWithExpire(Long id){
        //1、从redis缓存中查询看能不能查询到
        String shopJson = template.opsForValue().get(CACHE_SHOP_KEY + id);
        //2、判断redis是否有其内容，不存在直接返回
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //3、存在把json字符串反序列化对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4、判断是否过期，比较时间戳
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，返回缓存中的数据
            return shop;
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
               saveShop2Redis(id,20L);
            });
        }
        //5.3未获取成功则代表已经有线程在更新数据，先返回过期信息
        return shop;
    }

    //缓存穿透
    public Shop queryWithPassThrough(Long id){
        //1、从redis缓存中查询看能不能查询到
        String shopJson = template.opsForValue().get(CACHE_SHOP_KEY + id);
        //2、判断redis是否有其内容，有就返回，没有就对数据库进行查询
        if(StrUtil.isNotBlank(shopJson)){
            //将json转为对象
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            Shop shop = JSON.parseObject(shopJson,Shop.class);
//            return Result.ok(shop);
            return shop;
        }

        //3.1缓存穿透后续判断，存入""到数据库中，不让其再访问数据库；shopJson前面已经做了判断，只有null和""
        if(shopJson != null){
//            return Result.fail("店铺不存在");
            return null;
        }

        //3、对数据库查询，不存在就报错
        Shop shop = getById(id);
        if(shop == null){
            //3.1添加新功能，防止缓存穿透
            template.opsForValue().set(CACHE_SHOP_KEY+id,"",2,TimeUnit.MINUTES);
//            return Result.fail("店铺不存在");
            return null;
        }
        //4、数据库存在就存入redis缓存中提高下一次查询效率
        //4.1添加新功能：缓存更新策略；即当后端更改数据时要保证数据库和redis缓存之间的一致性
        //将对象再次转化为json字符串存储

//        template.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop));
        template.opsForValue().set(CACHE_SHOP_KEY+id,JSON.toJSONString(shop),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);//设置缓存有效期
//        return Result.ok(shop);
        return shop;
    }


    //自定义添加逻辑过期函数，同样是重建函数
    public void saveShop2Redis(Long id,Long expire){
        //1、调用数据库查询店铺数据
        Shop shop = getById(id);
        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expire));
        //3、序列化形式存入redis中
        template.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
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


    @Override
    public Result update(Shop shop) {
        //更新前判断此店铺Id是否存在
        if(shop.getId() == null){
            return Result.fail("店铺ID禁止为空");
        }

        //1、更新数据库
        updateById(shop);
        //2、删除缓存
        template.delete(CACHE_SHOP_KEY+shop.getId());

        return Result.ok();
    }
}
