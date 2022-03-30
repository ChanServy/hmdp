package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.SeckillVoucher;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Repository
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {
    @Update("update tb_seckill_voucher set stock = stock - 1 where voucher_id = #{voucherId} and stock > 0")
    int updateStock(Long voucherId);
}
