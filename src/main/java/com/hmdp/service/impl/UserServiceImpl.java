package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;


import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate template;

    @Override
    public Result sentCode(String phone, HttpSession session) {
        //1、校验手机号(调用工具类校验)
        if(RegexUtils.isPhoneInvalid(phone)){
            //仅仅只是校验手机号位数，还未校验是否注册
            return Result.fail("手机格式错误");
        }
        String code = RandomUtil.randomNumbers(6);//调用hutool工具包

        //2、生成验证码保存在Redis中，以phone为key，并添加字段标识符，设置过期时间为1min
        template.opsForValue().set(LOGIN_CODE_KEY+"phone",code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //3、发送验证码 TODO 暂时未加入
        log.debug("发送验证码成功，验证码为:"+code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1、再次检验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机格式错误");
        }
        //2、检验验证码
        String code = template.opsForValue().get(LOGIN_CODE_KEY+"phone");
        String oldCode = loginForm.getCode();
        if(code == null || !code.equals(oldCode)){
            return Result.fail("验证码错误");
        }
        //3、都检验一致时根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //4、判断用户是否存在，不存在创建新用户
        if(user == null){
            user = createUserWithPhone(phone);
        }

        //5、保存信息到Redis中
        //5.1随机生成token作为登录校验
        String token = UUID.randomUUID().toString(true);

        //5.2将User对象转为HashMap存储，存储前先转为userDTO对象节省内存
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        //此处涉及到Long转String问题，StringTemplate底层只能存储String，即此时的Map的key和value都必须为String
        //1、自己创建一个MAP
        Map<String,String> userMap = new HashMap<>();
        userMap.put("id",userDTO.getId().toString());
        userMap.put("nickName",userDTO.getNickName());
        userMap.put("icon",userDTO.getIcon());
        //2、重写里面工具类的参数，这个没必要写，复杂又没用
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
//                CopyOptions.create().
//                        setIgnoreNullValue(true)
//                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));


        //5.3存储并设置token有效期
        String tokenKey = LOGIN_USER_KEY + token;
        template.opsForHash().putAll(tokenKey,userMap);
        template.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    public User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(9));//设置随即用户名
        //保存user
        save(user);
        return user;
    }
}
