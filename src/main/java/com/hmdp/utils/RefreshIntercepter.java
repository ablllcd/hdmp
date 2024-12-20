package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshIntercepter implements HandlerInterceptor {
    public StringRedisTemplate stringRedisTemplate;

    public RefreshIntercepter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取token
        String uuid = request.getHeader("authorization");
        if (uuid == null) {
            return true;
        }

        // 2.根据token去redis查找用户
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(LOGIN_USER + uuid);
        if (entries.isEmpty()) {
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);

        // 3.保存用户信息到 ThreadLocal
        UserHolder.saveUser(userDTO);

        // 4.刷新redis中user的有效期
        stringRedisTemplate.expire(LOGIN_USER + uuid,LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 5.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
