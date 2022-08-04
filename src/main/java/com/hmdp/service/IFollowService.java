package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注或取关
     * @param followUserId 当前用户要关注的用户id，也就是被关注的用户id
     * @param isFollow 关注OR取关
     * @return Result
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断是否关注了
     * @param followUserId 被关注的用户id
     * @return Result
     */
    Result isFollow(Long followUserId);
}
