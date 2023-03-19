package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author cgJavaAfter
 * @date 2023-03-07 15:18
 */
//集成redisson
@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient(){
        //配置类
        Config config = new Config();
        //配置redis地址，设置端口和密码；使用useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://192.168.223.129:6379").setPassword("asuna");
        //创建客户端
        return Redisson.create(config);
    }
}
