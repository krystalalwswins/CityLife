package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RocketMQConstants;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_TTL_MINUTES;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final String SECKILL_ORDER_KEY_PREFIX = "seckill:order:";
    private static final int SECKILL_RESULT_OUT_OF_STOCK = 1;
    private static final int SECKILL_RESULT_DUPLICATE = 2;
    private static final int SECKILL_RESULT_STOCK_NOT_READY = 3;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private ObjectMapper objectMapper;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result buyVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null || !Integer.valueOf(1).equals(voucher.getStatus())) {
            return Result.fail("优惠券不存在或已下架");
        }
        if (Integer.valueOf(1).equals(voucher.getType())) {
            return Result.fail("该优惠券为秒杀券，请使用秒杀方式购买");
        }

        long orderId = redisIdWorker.nextId("order");
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(VoucherOrder.STATUS_UNPAID);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        boolean saved = save(order);
        if (!saved) {
            return Result.fail("普通券订单创建失败，请稍后重试");
        }
        return Result.ok(orderId);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = executeSeckillScript(voucherId, userId);
        if (result == null) {
            log.error("秒杀脚本执行结果为空，voucherId={}, userId={}", voucherId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }

        int r = result.intValue();
        if (r == SECKILL_RESULT_STOCK_NOT_READY) {
            if (!tryWarmupSeckillStock(voucherId)) {
                log.warn("秒杀券不存在或未配置库存，voucherId={}", voucherId);
                return Result.fail("该优惠券未配置秒杀活动或库存");
            }
            result = executeSeckillScript(voucherId, userId);
            if (result == null) {
                return Result.fail("系统繁忙，请稍后重试");
            }
            r = result.intValue();
        }

        if (r != 0) {
            if (r == SECKILL_RESULT_OUT_OF_STOCK) {
                return Result.fail("库存不足");
            }
            if (r == SECKILL_RESULT_DUPLICATE) {
                return Result.fail("不能重复下单");
            }
            if (r == SECKILL_RESULT_STOCK_NOT_READY) {
                log.warn("秒杀库存初始化后仍未生效，voucherId={}", voucherId);
                return Result.fail("秒杀库存未初始化，请稍后重试");
            }
            return Result.fail("系统繁忙，请稍后重试");
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(VoucherOrder.STATUS_UNPAID);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());

        markOrderPending(orderId, userId);
        try {
            rocketMQTemplate.syncSend(RocketMQConstants.SECKILL_ORDER_DESTINATION, objectMapper.writeValueAsString(order));
        } catch (JsonProcessingException e) {
            clearOrderPending(orderId);
            rollbackSeckillReservation(voucherId, userId);
            log.error("秒杀订单序列化失败，订单ID={}", orderId, e);
            return Result.fail("订单创建失败，请稍后重试");
        } catch (Exception e) {
            clearOrderPending(orderId);
            rollbackSeckillReservation(voucherId, userId);
            log.error("RocketMQ 秒杀订单消息发送失败，订单ID: {}", orderId, e);
            return Result.fail("系统繁忙，请稍后重试");
        }

        try {
            rocketMQTemplate.syncSendDelayTimeMills(
                    RocketMQConstants.ORDER_TIMEOUT_DESTINATION,
                    String.valueOf(orderId),
                    RocketMQConstants.ORDER_PAYMENT_TIMEOUT_MILLIS
            );
            log.info("订单超时延迟消息已发送，订单ID={}, 15分钟后自动关闭", orderId);
        } catch (Exception e) {
            log.error("订单超时延迟消息发送失败，订单ID: {}", orderId, e);
        }

        return Result.ok(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        VoucherOrder existingOrder = getById(voucherOrder.getId());
        if (existingOrder != null) {
            log.warn("秒杀订单已存在，忽略重复消息，订单ID={}", voucherOrder.getId());
            clearOrderPending(voucherOrder.getId());
            return;
        }

        boolean saved = save(voucherOrder);
        if (!saved) {
            rollbackSeckillReservation(voucherOrder.getVoucherId(), voucherOrder.getUserId());
            throw new IllegalStateException("保存秒杀订单失败，订单ID=" + voucherOrder.getId());
        }

        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!stockUpdated) {
            rollbackSeckillReservation(voucherOrder.getVoucherId(), voucherOrder.getUserId());
            throw new IllegalStateException("扣减秒杀库存失败，券ID=" + voucherOrder.getVoucherId());
        }

        clearOrderPending(voucherOrder.getId());
        log.info("秒杀订单落库成功，订单ID={}", voucherOrder.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void closeTimeoutOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            log.warn("订单不存在，订单ID={}", orderId);
            clearOrderPending(orderId);
            return;
        }

        if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
            log.info("订单状态不是未支付，无需关闭，订单ID={}, 状态={}", orderId, order.getStatus());
            return;
        }

        order.setStatus(VoucherOrder.STATUS_CANCELLED);
        order.setUpdateTime(LocalDateTime.now());
        if (!updateById(order)) {
            throw new IllegalStateException("关闭超时订单失败，订单ID=" + orderId);
        }

        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!stockUpdated) {
            throw new IllegalStateException("回滚秒杀库存失败，订单ID=" + orderId);
        }

        rollbackSeckillReservation(order.getVoucherId(), order.getUserId());
        clearOrderPending(orderId);
        log.info("订单超时已关闭，订单ID={}", orderId);
    }

    private Long executeSeckillScript(Long voucherId, Long userId) {
        return stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
    }

    private boolean tryWarmupSeckillStock(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null || seckillVoucher.getStock() == null) {
            return false;
        }
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, seckillVoucher.getStock().toString());
        log.info("检测到秒杀库存缺失，已按需补热到 Redis，voucherId={}, stock={}", voucherId, seckillVoucher.getStock());
        return true;
    }

    private void markOrderPending(Long orderId, Long userId) {
        stringRedisTemplate.opsForValue().set(
                SECKILL_ORDER_PENDING_KEY + orderId,
                String.valueOf(userId),
                SECKILL_ORDER_PENDING_TTL_MINUTES,
                TimeUnit.MINUTES
        );
    }

    private void clearOrderPending(Long orderId) {
        stringRedisTemplate.delete(SECKILL_ORDER_PENDING_KEY + orderId);
    }

    private void rollbackSeckillReservation(Long voucherId, Long userId) {
        try {
            stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherId);
            stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY_PREFIX + voucherId, String.valueOf(userId));
        } catch (Exception e) {
            log.error("回滚 Redis 秒杀占用失败，voucherId={}, userId={}", voucherId, userId, e);
        }
    }
}