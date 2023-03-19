package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private FollowMapper followMapper;
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    String keyPrefix = "follows:";

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1、获取到用户的id，完成用户表和关注表的双向绑定
        Long userId = UserHolder.getUser().getId();
        String key = keyPrefix + userId;
        //2、对传入的isFollow进行判断
        if(isFollow){
            //为ture时进行添加操作
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            //保存数据库
            boolean isFlag = save(follow);
            //将关注关系保存在redis的set结构中，根据此结构求共同关注
            if(isFlag){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //此时进行取关操作，删除表数据
            int isFlag = followMapper.DeleteFollow(userId, followUserId);
            if(isFlag > 0){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1、获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2、查询follow表是否存在此userId，存在就表示已经关注，将其结果返回前端
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        //1、获取当前用户Id
        Long userId = UserHolder.getUser().getId();
        //2、包装成key
        String key1 = keyPrefix + userId;
        String key2 = keyPrefix + id;
        //3、根据redis求共同关注
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //4、解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //5、根据Id查询用户信息返回前端显示
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
