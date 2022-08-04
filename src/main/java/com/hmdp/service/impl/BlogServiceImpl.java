package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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
    private StringRedisTemplate stringRedisTemplate;

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
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 查询blog相关的用户
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //查询blog是否被点赞过
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录时，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        //判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        // Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + id;
        // Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //如果未点赞，可以点赞
            UpdateWrapper<Blog> updateWrapper = new UpdateWrapper<>();
            //数据库点赞数+1
            updateWrapper.setSql("liked = liked + 1");
            updateWrapper.eq("id", id);
            Blog blog = new Blog();
            int update = this.baseMapper.update(blog, updateWrapper);
            if (update == 1) {
                //保存用户到redis的set集合 zadd key value score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //如果已点赞
            //数据库点赞数-1
            //如果未点赞，可以点赞
            UpdateWrapper<Blog> updateWrapper = new UpdateWrapper<>();
            //数据库点赞数+1
            updateWrapper.setSql("liked = liked - 1");
            updateWrapper.eq("id", id);
            Blog blog = new Blog();
            int update = this.baseMapper.update(blog, updateWrapper);
            //把用户从redis的set集合中移除
            if (update == 1) {
                //保存用户到redis的set集合
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return null;
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5的点赞用户 zrange key 0 4
        String key = BLOG_LIKED_KEY + id;
        // range出来的结果默认就是按照score排序的
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (!CollectionUtils.isEmpty(top5)) {
            List<UserDTO> userDTOS = top5.stream().map((userIdStr) -> {
                Long userId = Long.valueOf(userIdStr);
                //根据用户id查询用户
                User user = userService.getById(userId);
                UserDTO userDTO = new UserDTO();
                BeanUtils.copyProperties(user, userDTO);
                return userDTO;
            }).collect(Collectors.toList());
            //返回
            return Result.ok(userDTOS);
        } else {
            return Result.ok(Collections.emptyList());
        }
    }
}
