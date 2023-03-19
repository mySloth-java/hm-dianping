package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        //1、根据id查询Blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("欸，该博客已经跑不见了捏!");
        }
        //2、根据blog查询有关用户，得到用户信息
        extracted(blog);
        //3、查询此用户是否已经为该博客点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    //查询blog是否点过赞
    private void isBlogLiked(Blog blog) {
        //优化：防止未登录导致查询时的空指针
        UserDTO user = UserHolder.getUser();
        if(user == null){
            //当用户未登陆时，无需查询点赞
            return;
        }

        //1、获取用户Id
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked:" + blog.getId();
        Double isScore = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //2、为blog的flag熟悉赋值
        blog.setIsLike(isScore != null);
    }

    //查询blog有关用户
    private void extracted(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            extracted(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    //点赞功能
    @Override
    public Result likeBlog(Long id) {
        //1、获取用户id，并根据传入的blogId确认在redis数据库中是否被添加过
        Long userId = UserHolder.getUser().getId();
        String key  = "blog:liked:" + id;
        Double isScore = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //2、判断是否存在
        if(isScore == null){
            //此时表示该用户没有点赞，调用数据库进行like+1操作
            boolean isFlag = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isFlag){
                //数据库成功时，对redis缓存进行数据同步
//                stringRedisTemplate.opsForSet().add(key,userId.toString());
                //优化：使用zSet存储，便于点赞排序，将时间戳作为score存储，根据score判断是否存在
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            //存在表示该用户已经点过赞了，对赞进行-1操作，并将此set集合从redis中移除
            boolean isFlag = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isFlag){
                //数据库成功时，对redis缓存进行数据同步
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    //点赞排行榜
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //1、通过redis缓存查询top5的点赞用户
        Set<String> topFive = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(topFive == null || topFive.isEmpty()){
            //当没有点赞用户时返回空集合
            return Result.ok(Collections.emptyList());
        }
        //2、解析用户id
        List<Long> userIds = topFive.stream().map(Long::valueOf).collect(Collectors.toList());
        //3、根据用id查询用户得到用户信息，并将其封装成UserDTO
        List<UserDTO> userDTO = userService.listByIds(userIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTO);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isFlag = save(blog);
        if(!isFlag){
            return Result.fail("新增笔记失败");
        }
        //3、查询笔记作者的粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4、推送笔记
        for(Follow follow : follows){
            //获取粉丝id
            Long userId = follow.getUserId();
            //推送
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1、获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2、查询收件箱
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 4);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();//非空判断
        }
        //3、解析数据，得到blogId、minTime、offset
        ArrayList<Object> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int count = 1;//统计最小数的出现次数
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples){
            //获取id，并存入list集合
            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数时间戳
            long time = tuple.getScore().longValue();
            if(time == minTime){
                count ++;//如果两数相等就让其统计++
            }else {
                minTime = time;
                count = 1;
            }
        }
        //4、根据id查询blog
        String idStr = StrUtil.join(",", ids);//拼接成字符串
        List<Blog> blogs = query().in("id", ids)//拼接sql语句，防止原生in破坏排序顺序
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        //查询是否点过赞
        for(Blog blog: blogs){
            extracted(blog);
            isBlogLiked(blog);
        }
        //5、封装结果DTO返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(count);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
