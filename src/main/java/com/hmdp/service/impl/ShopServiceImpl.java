package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 1.从redis缓存中查找
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP + id);
        if (StrUtil.isNotBlank(shopJson)) {
            // 2.如果缓存中有则直接返回
            System.out.println("shop id 缓存命中");
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 2.1 如果缓存中是空值，也进行返回
        if(shopJson != null){
            return Result.fail("shop is not find");
        }
        // 3.缓存未命中则从数据库查找
        Shop shop = getById(id);
        // 4.如果数据库未命中则返回错误信息
        if (shop == null){
            // 4.1 缓存空值，避免缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP+id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("shop is not find");
        }
        // 5.数据库找到则放入缓存中并设置过期时间
        stringRedisTemplate.opsForValue().set(CACHE_SHOP+id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 6.返回shop
        return Result.ok(shop);
    }

    @Override
    public Result queryByIDWithCacheClient(Long id) {
        Shop shop = cacheClient.getWithPassThrough(CACHE_SHOP + id, Shop.class, id, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null){
            return Result.fail("shop is not find");
        }
        return Result.ok(shop);
    }

    @Override
    // 该方法实现互斥锁解决雪崩
    public Result queryByIdWithMutex(Long id) {
        // 1.从redis缓存中查找
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP + id);
        if (StrUtil.isNotBlank(shopJson)) {
            // 2.如果缓存中有则直接返回
            System.out.println("shop id 缓存命中");
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 2.1 如果缓存中是空值，也进行返回
        if(shopJson != null){
            return Result.fail("shop is not find");
        }

        // 3. 如果缓存中没有则尝试进行缓存重构
        Shop shop = null;
        try {
            while (!tryLock(CACHE_SHOP_LOCK+id)) {
                // 3.1. 没拿到锁则等待
                Thread.sleep(50);
            }

            // 3.2 拿到锁后也要判断是否别人已经重构过了（判断自己是否为等待线程）
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP + id);
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return Result.ok(shop);
            }

            // 4. 这里开始重构缓存：从数据库查找并放入缓存
            shop = getById(id);
            if (shop == null){
                // 4.1 数据库没找到，重构失败，放入空值
                stringRedisTemplate.opsForValue().set(CACHE_SHOP+id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("shop is not find");
            }
            // 5.数据库找到则放入缓存中并设置过期时间
            stringRedisTemplate.opsForValue().set(CACHE_SHOP+id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 6. 释放锁
            releaseLock(CACHE_SHOP_LOCK+id);
        }

        // 7.返回shop
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("shop id is null");
        }

        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP+id);

        return Result.ok();
    }

    private boolean tryLock(String lock){
        // 利用setnx命令来实现互斥锁的功能
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(lock, "1", CACHE_SHOP_LOCK_TTL, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(aBoolean);
    }

    private void releaseLock(String lock){
        stringRedisTemplate.delete(lock);
    }
}
