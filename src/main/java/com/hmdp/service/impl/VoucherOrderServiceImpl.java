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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
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

    private static DefaultRedisScript<Long> seckillScript;
    static {
        // 加载Lua脚本
        seckillScript = new DefaultRedisScript<>();
        seckillScript.setLocation(new ClassPathResource("buySeckillVoucher.lua"));
        seckillScript.setResultType(Long.class);
    }

    // 阻塞队列来存储要处理的订单
    private final BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024);
    // 线程池来处理阻塞队列里的订单
    private static final ExecutorService bqExecutor = Executors.newSingleThreadExecutor();
    // 线程池要用到@Transactional注解的方法，所以需要代理对象；而代理对象的获取是通过threadLocal拿的，线程池本身拿不了，只能从成员变量拿
    private IVoucherOrderService proxy;
    //  线程池处理订单的逻辑
    private class orderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 从阻塞队列来拿订单
                    VoucherOrder order = blockingQueue.take();
                    // 2. 在数据库完成对订单的处理
                    proxy.createOrderInDB(order);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Transactional
    public void createOrderInDB(VoucherOrder order) {
        Long voucherId = order.getVoucherId();

        // 1. 扣减秒杀券库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.debug("库存扣减失败");
        }

        // 2. 创建购买秒杀券的订单
        save(order);
    }

    @PostConstruct
    private void init(){

        // 类初始化时，线程池开始执行处理阻塞的任务
        bqExecutor.submit(new orderHandler());
    }

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

    // 该方法使用redis+消息队列来优化createOrder方法
    @Override
    public Result createOrder2(long voucherID) {
        Long userID = UserHolder.getUser().getId();
        // 1. 执行Lua脚本，完成缓存中的扣减库存和添加订单
        Long result = stringRedisTemplate.execute(seckillScript, Collections.emptyList(),
                voucherID + "", userID.toString());

        // 如果订单创建失败则返回错误信息
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "每个用户只能购买一次");
        }

        // 2. 创建消息队列，让线程池在数据库完成订单业务
        // 2.1 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderID = idGenerator.nextID("voucher-order");
        voucherOrder.setId(orderID);
        voucherOrder.setVoucherId((long) voucherID);
        voucherOrder.setUserId(userID);
        // 2.2 添加订单任务到阻塞队列，处理订单等线程池来做就行
        blockingQueue.add(voucherOrder);
        // 2.3 设置代理对象，为了让线程池能调用@Transactional注解的方法
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3. 返回订单ID
        return Result.ok(orderID);
    }




}
