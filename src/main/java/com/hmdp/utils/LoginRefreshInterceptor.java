package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author cgJavaAfter
 * @date 2023-03-02 22:48
 */

/**
 * 此拦截器主要是为了刷新登录用户的token，让其实时保持登录状态，如果放在一个拦截器有弊端，
 *  只有登录时候才会刷新token，如果一直不登陆看非拦截页面就不会刷新token
 */
public class LoginRefreshInterceptor implements HandlerInterceptor {
    //此类不是spring注解的类，所以需要自己创建构造器使用，或者通过有注解的类使用此类，在另一个有注解的类注解
    private StringRedisTemplate template;

    public LoginRefreshInterceptor(StringRedisTemplate template) {
        this.template = template;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1、获取存储在Header中的token信息
        String token = request.getHeader("authorization");
        //2、判断token是否存在，不再需要拦截了，直接放行交给第二个拦截器
        if(StrUtil.isBlank(token)){
            return true;
        }
        //3、根据token查询Redis表中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = template.opsForHash().entries(key);
        //4、判断用户是否存在，直接放行交给第二个拦截器
        if(userMap.isEmpty()){
            return true;
        }
        //5、将hash转为userDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //6、存储用户到ThreadLocal中
        UserHolder.saveUser(userDTO);
        //7、刷新token
        template.expire(key,30, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();//移除用户

    }
}
