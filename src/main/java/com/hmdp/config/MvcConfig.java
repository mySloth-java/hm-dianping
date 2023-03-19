package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.LoginRefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author cgJavaAfter
 * @date 2023-03-01 22:28
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate template;

    //添加拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(//配置不登陆可以访问的内容
                        "/user/code",
                        "/user/login",
                        "/shop/**",
                        "/upload/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/blog/hot"
                ).order(1);//第二层拦截器
        registry.addInterceptor(new LoginRefreshInterceptor(template)).order(0);//设置优先级，第一层拦截器
    }
}
