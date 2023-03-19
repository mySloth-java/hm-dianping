package com.hmdp;


import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void saveShopRedisTest(){
        shopService.saveShop2Redis(1L,20L);
    }

    //GEO实现附近店铺数据预热
    @Test
    void loadShopData(){
        //1、查询店铺信息
        List<Shop> shops = shopService.list();
        //2、根据typeId对店铺进行分组，不再需要过滤操作
        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3、菲尼写入redis
        for(Map.Entry<Long,List<Shop>> entry : map.entrySet()){
            //获取typeId
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            //获取同类型的店铺集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //写入redis中 GEOADD key 经纬度 member
            for(Shop shop : value){
                //直接使用redis写入，但耗费大
//                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());

                //传入location集合参数
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            //最后把locations写入redis，防止多次写入
            stringRedisTemplate.opsForGeo().add(key,locations);

        }

    }


}
