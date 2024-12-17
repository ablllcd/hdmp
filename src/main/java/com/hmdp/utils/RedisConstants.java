package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE = "login:code:";
    public static final long LOGIN_CODE_TTL = 2;

    public static final String LOGIN_USER = "login:user:";
    public static final long LOGIN_USER_TTL = 30;

    public static final String CACHE_SHOP = "cache:shop:";
    public static final long CACHE_SHOP_TTL = 10;
    public static final long CACHE_NULL_TTL = 3;

    public static final long CACHE_SHOP_LOCK_TTL = 3;
    public static final String CACHE_SHOP_LOCK = "cache:shop:lock:";

    public static final String CACHE_SHOP_TYPE_LIST = "cache:shop-type:list";

}
