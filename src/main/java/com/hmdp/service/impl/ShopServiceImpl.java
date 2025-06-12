package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
@JsonFormat
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        //缓存穿透解决缓存击穿
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2->getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //用互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, id2->getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, id2->getById(id2), 20L, TimeUnit.SECONDS);

        if(shop == null){
            return Result.fail("店铺不存在");
        }
        //7.返回
        return  Result.ok(shop);
    }

    //缓存穿透
    public Shop queryWithPassThrough (Long id){
        //1.从redis中查询商户信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        //2.判断redis中商户是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回（有值的情况）
            return JSONUtil.toBean(shopJson, Shop.class);//string转成对象;
        }
        //判断命中的是否是空值(空字符串)
        if(shopJson!=null){
            return null;
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.不存在，返回错误
        if(shop==null){
            //将空值写入redis（避免缓存穿透，给数据库带来压力）
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，保存商户信息到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);//对象转成string
        //7.返回
        return shop;
    }


    private boolean tryLock(String key){// 尝试获取锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent( key, "1", 10, TimeUnit.SECONDS);//setIfAbsent如果存在
        return BooleanUtil.isTrue(flag);//拆箱
    }
    private void unLock(String key){// 释放锁
        stringRedisTemplate.delete(key);
    }
    //互斥锁解决缓存击穿（雪崩的redis宕机，击穿是太多相同的key访问(都是为了防止数据库压力过大)）
    public Shop queryWithMutex (Long id){
        //1.从redis中查询商户信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        //2.判断redis中商户是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在，直接返回（有值的情况）
            return JSONUtil.toBean(shopJson, Shop.class);//string转成对象;
        }
        //判断命中的是否是空值(空字符串)
        if(shopJson!=null){
            return null;
        }
        //4.实现缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean isLock =tryLock(lockKey);
            //4.2 判断是否获取成功
            if(!isLock){
                //4.3 失败，则休眠重试
                    Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 成功，根据id查询数据库
            shop = getById(id);
            //5.不存在，返回错误
            if(shop==null){
                //将空值写入redis（避免缓存穿透，给数据库带来压力）
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，保存商户信息到redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);//对象转成string
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally{
            //7.释放互斥锁
            unLock(lockKey);
        }
        //8.返回
        return shop;
    }




    //尝试写入逻辑过期时间
    private void saveShop2Redis(Long id,Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    // 逻辑过期
    //创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire (Long id){
        //1.从redis中查询商户信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
        //2.判断redis中商户是否存在
        if(StrUtil.isBlank(shopJson)){
            //3.存在，直接返回（有值的情况）
            return null;
        }
        //4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);//强转后转成对象
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return shop;
        }
        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if(isLock){
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try{
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4返回过期的商铺信息
        return shop;
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
         if(id==null){
              return Result.fail("店铺id不能为空");
         }
        //1.更新数据库(先更新再删除保持一致性)
        updateById(shop);
        //2.删缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return null;
    }
}
