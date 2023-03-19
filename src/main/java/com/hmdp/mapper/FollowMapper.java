package com.hmdp.mapper;

import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface FollowMapper extends BaseMapper<Follow> {

    //自定义删除
    int DeleteFollow(@Param("userId") Long userId,@Param("followUserId") Long followUserId);

}
