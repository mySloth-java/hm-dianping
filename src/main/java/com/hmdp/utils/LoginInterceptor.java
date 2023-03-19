package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author cgJavaAfter
 * @date 2023-03-01 22:23
 */
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //此拦截器只需要判断是否有用户即可(ThreadLocal是否有用户存在)
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        return true;
    }


}
