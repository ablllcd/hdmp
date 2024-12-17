package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_LIST;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getList() {
        // 1.尝试从缓存中获取
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_LIST);
        // 2.如果命中则直接返回
        if(StrUtil.isNotBlank(shopTypeJson)){
            System.out.println("shop type 缓存命中");
            List<ShopType> shopType = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopType);
        }
        // 3.未命中则从数据库查找
        List<ShopType> shopType = query().orderByAsc("sort").list();
        if (shopType == null){
            // 4.数据库中没有则返回错误信息
            return Result.fail("shop type list is not found");
        }

        // 5.数据库有则记录到缓存并返回
        shopTypeJson = JSONUtil.toJsonStr(shopType);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_LIST, shopTypeJson);

        return Result.ok(shopType);
    }
}
