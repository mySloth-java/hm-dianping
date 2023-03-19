package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWork;
import com.hmdp.utils.RedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate template;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisIdWork redisIdWork;

    //创建异步线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct//要在依赖加载后，对象使用前执行，而且只执行一次
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream-orders";
        @Override
        public void run() {
            while (true){
                try {
                    //1、从消息队列中获取信息；等同XREADGROUP语句
                    List<MapRecord<String, Object, Object>> read = template.opsForStream().read(
                            Consumer.from("evaDepart", "c1"),//group 和 consumer
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),//查询最大数量 和 最大等待时间
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())//指定队列 和 起始id(从下一个未消费消息开始)
                    );
                    //2、判断消息是否获取成功
                    if(read == null || read.isEmpty()){
                        continue;//获取失败继续下次循环，知道获取消息为止
                    }
                    //3、解析消息的订单信息
                    //String存储的是消息的id
                    MapRecord<String, Object, Object> entries = read.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(),true);
                    //4、获取成功进行下单

                    System.out.println("voucher:"+voucherOrder);

                    handleVoucherOrder(voucherOrder);
                    //5、在读取消息后进行ACK确认，从queue移除
                    template.opsForStream().acknowledge(queueName,"evaDepart",entries.getId());
                } catch (Exception e) {
                    log.error("订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //此时由于是异步线程开启的任务，获取不到localHolder
            Long userId = voucherOrder.getUserId();

            //4.1创建锁对象
            RedisLock redisLock = new RedisLock("order:" + userId, template);
            //4.2获取锁，设置超时时间
            boolean lockFlag = redisLock.tryLock(5);
            //4.3判断是否获取成功
            if(!lockFlag){
                log.error("禁止重复下单");
            }
            //4.4处理异常并释放锁
            try {
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                //释放锁
                redisLock.unLock();
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //1、从pending中获取信息；等同XREADGROUP语句
                    List<MapRecord<String, Object, Object>> read = template.opsForStream().read(
                            Consumer.from("evaDepart", "c1"),
                            StreamReadOptions.empty().count(1),//查询最大数量
                            StreamOffset.create(queueName, ReadOffset.from("0"))//指定队列 和 起始id
                    );
                    //2、判断消息是否获取成功
                    if(read == null || read.isEmpty()){
                        break;
                    }
                    //3、解析消息的订单信息
                    //String存储的是消息的id
                    MapRecord<String, Object, Object> entries = read.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(),true);
                    //4、获取成功进行下单
                    handleVoucherOrder(voucherOrder);
                    //5、在读取消息后进行ACK确认，从queue移除
                    template.opsForStream().acknowledge(queueName,"evaDepart",entries.getId());
                } catch (Exception e) {
                    log.error("pending订单异常",e);
                }
            }
        }
    }

    //加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("scekill.lua"));//设置加载lua脚本位置
        SECKILL_SCRIPT.setResultType(Long.class);//设置返回类型
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWork.nextId("order");
        //1、执行脚本；脚本内完成了对消息队列的信息发送
        Long result = template.execute(
                SECKILL_SCRIPT,//参数1：lua脚本
                Collections.emptyList(),//参数2：key，没有Key用空集合代替
                voucherId.toString(), userId.toString(),String.valueOf(orderId)//参数3：value值集合
        );
        //2、根据lua脚本判断是否为0，非0不能购买
        if(result.intValue() != 0){
            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能多次下单");
        }

        //获取代理对象供使用
        proxy= (IVoucherOrderService)AopContext.currentProxy();

        //3、在程序开启时，开启异步线程读取消息队列内容即可
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1、通过优惠券id查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2、判断秒杀是否开始和结束
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        //3、判断库存是否充足
//        if(voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//
//        //分布式锁优化2：集成redisson
//        //4.1获取锁(可重入)
//        RLock lock = redissonClient.getLock("order:" + userId);
//        boolean tryLock = lock.tryLock();//有3个参数，分别为锁的最大等待时间(会重试)、锁自动释放时间、单位
//        //4.2判断释放获取成功
//        if(!tryLock){
//            return Result.fail("禁止重复下单");
//        }
//        //4.3处理异常并释放锁
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//           lock.unlock();
//        }



        //分布式锁优化1：redis实现分布式锁，解决了分布式系统问题
//        //4.1创建锁对象
//        RedisLock redisLock = new RedisLock("order:" + userId, template);
//        //4.2获取锁，设置超时时间
//        boolean lockFlag = redisLock.tryLock(5);
//        //4.3判断是否获取成功
//        if(!lockFlag){
//            return Result.fail("禁止重复下单");
//        }
//        //4.4处理异常并释放锁
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            //释放锁
//            redisLock.unLock();
//        }

        //此分布式不能阻止分布式服务的请求
        /**
         * 此处要注意锁的时机和位置：
         * 时机：如果在方法内部锁，那么锁会在事务提交前被释放，然后事务被提交，如果提交前有数据请求，就会有误差
         * 位置：如果锁整个方法，那么无论是不是同一个用户，都会分配一个锁，大大浪费了效率，实际只需要为相同用户分配一个
         * 锁即可，铜鼓将Long转为String，然后通过intern()得到每一个字符串常量池的位置，保证每一个用户对应一个
         * 字符串常量池
         */
//        synchronized (userId.toString().intern()){
//            /**
//             * 但由于事务是对此方法添加的，而默认调用的是this.
//             * 事务生效的本质是spring通过获取到此类的代理对象，而此时的this方法是非代理对象，这也是事务失效的情况之一
//             */
//            //获取到当前的代理对象(接口)，对应的接口也要创建此方法
//            //解决方法2：注入自己
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
//    }


    @Transactional//对于操作2张以上表添加事务；由于此操作不涉及更新数据，所以需要用悲观锁
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //添加一人一单功能
        //1、获取到用户的Id
//        Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        //2、根据用户Id和voucherId查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        //3、判断是否存在
        if(count > 0){
            log.error("已经购买过了");
//            return Result.fail("已经购买过了!");
        }

        //3.1库存足够扣减库存；等同sql语句 update from .. set stock = stock -1 where id = ? and stock > 0
        boolean flag = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)//优化1：乐观锁优化，在库存足够时判断stock是否>0；但仍存在有库存少卖的问题
                .update();
        if(!flag){
            log.error("库存不足");
//            return Result.fail("库存不足");
        }

        //4、创建订单
        //此时的异步操作不需要创建订单，也不需要返回值了
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //4.1订单ID，使用id生成器
//        long orderId = redisIdWork.nextId("order");
//        voucherOrder.setId(orderId);
//        //4.2用户Id
//        voucherOrder.setUserId(userId);
//        //4.3代金券Id
//        voucherOrder.setVoucherId(voucherOrder);
        //4.4保存至数据库中
        save(voucherOrder);

        //5.返回订单Id
//        return Result.ok(orderId);
    }
}
