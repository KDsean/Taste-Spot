package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
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

    @Resource
    private ISeckillVoucherService seckillVoucherService;//秒杀券
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    //导入秒杀脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct//类初始化完后执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //创建子线程
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true){
                try{
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                }catch (Exception e){
                    log.error("订单处理异常",e);
                }
            }

        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //获取用户
            Long userId = voucherOrder.getUserId();
            //创建锁对象（实现多进程之间加锁）
            //Redission分布式锁创建锁对象
            RLock lock = redissonClient.getLock("lock:order:"+userId);
            //Redission获取锁对象
            boolean isLick = lock.tryLock();
            //判断是否获取锁成功
            if(!isLick){
                //获取锁失败返回错误信息
                log.error("不允许重复下单");
                return;
            }
            try{

                proxy.createVoucherOrder(voucherOrder);
            }finally {
                //释放锁
                lock.unlock();
            }
        }
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为0，有购买资格，把下单信息保存到阻塞队列（异步下单,开启独立线程从队列里拿去下单(把耗时较久的下单与数据库交互部分异步完成，主线程只判断资格)）
        //2.3 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.4 订单id
        orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.5 用户id
        voucherOrder.setUserId(userId);
        //2.6 代金券id
        voucherOrder.setVoucherId(voucherId);
        //2.7 放入阻塞队列
        orderTasks.add(voucherOrder);

        //3.获取代理对象
        proxy =(IVoucherOrderService) AopContext.currentProxy();//拿到事务代理对象
        //4.返回订单id
        return Result.ok(orderId);
    }



    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById((voucherId));
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否已经结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        if(voucher.getStock()<1){
            //库存不足
            return Result.fail("库存不足");
        }
        Long userId =UserHolder.getUser().getId();
        *//*synchronized(userId.toString().intern()){//(在此定义保证事务完整性)
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();//拿到事务代理对象
            return proxy.createVoucherOrder(voucherId);
        }*//*
        //创建锁对象（实现多进程之间加锁）
        //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
        //Redission分布式锁创建锁对象
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //获取锁
        //boolean isLick = lock.tryLock(1200);//自动释放时间为1200毫秒
        //Redission获取锁对象
        boolean isLick = lock.tryLock();
        //判断是否获取锁成功
        if(!isLick){
            //获取锁失败返回错误信息
            return Result.fail("不允许重复下单");
        }
        try{
            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();//拿到事务代理对象
            return proxy.createVoucherOrder(voucherId);
        }finally {
            //释放锁
            lock.unlock();
        }
    }*/

    //悲观锁：无论如何都会发生线程安全问题，每次操作数据前都要获取锁（慢，消耗大）例如：Synchronized，Lock
    //乐观锁：认为线程安全不一定发生，只是更新数据时判断有无其他线程对数据做了修改（用查询与修改时查询的库存值进行比较可以得出高并发场景下会不会有人同步修改）

    @Transactional//事务只是下面
    public void createVoucherOrder(VoucherOrder vouchOrder){
        // 5.一人一单
        //Long userId = UserHolder.getUser().getId();
        Long userId = vouchOrder.getUserId();

        //synchronized (userId.toString().intern()) {//同一个用户来了才判断线程安全问题（一人一单）没必要整个方法都加锁，优化内存占用，.intern()是在内存池里找一样的就是一个对象（保证id一样就一把一样的锁）
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", vouchOrder.getVoucherId()).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                //return Result.fail("用户已经购买过一次！");
                log.error("用户已经购买过一次！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", vouchOrder.getVoucherId())//比较voucher_id与stock是否相等来实现乐观锁
                    .gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                //return Result.fail("库存不足！");
                log.error("库存不足！");
                return;
            }

            /*// 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(vouchOrder);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);*/

            //7.创建订单（异步下单）
            save(vouchOrder);
    }
}
