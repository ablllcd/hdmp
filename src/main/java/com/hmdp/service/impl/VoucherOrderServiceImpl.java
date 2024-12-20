package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.IDGenerator;
import com.hmdp.utils.ILock;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private IDGenerator idGenerator;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public Result createOrder(long voucherID) {
        // 1. 查询秒杀券是否在活动中
        SeckillVoucher voucher = seckillVoucherService.getById(voucherID);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 1.1 活动未开始
            return Result.fail("活动未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 1.2 活动已经结束
            return Result.fail("活动已经结束");
        }

        // 2. 判断秒杀券是否还有库存
        if (voucher.getStock() < 1){
            return Result.fail("秒杀券已售完");
        }

        Long userID = UserHolder.getUser().getId();

//        // 使用intern是确保：id值相同时，string对象相同
//        synchronized (userID.toString().intern()) {
//            // 避免@Transactional注解失效
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrderInDB(voucherID, userID);
//        }

        // 使用分布式锁
//        ILock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userID);
//        boolean isLock = lock.tryLock(1200);
//        if (!isLock){
//            // 获取锁失败：说明该用户ID对应的锁已经存在，也就是该用户已经正在购买了
//            return Result.fail("一个用户只能下一单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrderInDB(voucherID, userID);
//        } finally {
//            lock.unlock();
//        }

        // 使用Redisson提供的分布式锁
        RLock lock = redissonClient.getLock("redisson-lock:order:" + userID);   //可重入锁
        boolean isLock = lock.tryLock();    //设置为不等待
        if (!isLock) {
            // 获取锁失败：说明该用户ID对应的锁已经存在，也就是该用户已经正在购买了
            return Result.fail("一个用户只能下一单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createOrderInDB(voucherID, userID);
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public Result createOrderInDB(long voucherID, long userID){
        // 3. 确保每个用户只能有一单
        QueryChainWrapper<VoucherOrder> eqUserVoucher = query().eq("voucher_id", voucherID).eq("user_id", userID);
        if (eqUserVoucher.count() > 0) {
            return Result.fail("一人只能买一个");
        }

        // 3. 扣减秒杀券库存
        // 由于数据库操作本身是加锁的，所以我们可以当作它们是原子的。那在这次原子操作中，额外判断库存是否>0即可避免超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherID)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存扣除失败");
        }

        // 4. 创建购买秒杀券的订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderID = idGenerator.nextID("voucher-order");
        voucherOrder.setId(orderID);
        voucherOrder.setVoucherId((long) voucherID);
        voucherOrder.setUserId(userID);
        save(voucherOrder);

        return Result.ok(orderID);
    }
}
