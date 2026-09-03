package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IPaymentService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.ORDER_PAYMENT_LOCK_PREFIX;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_PENDING_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements IPaymentService {

    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public Result createPayment(Long orderId, Integer payType) {
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }

        if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
            return Result.fail("订单状态不正确");
        }

        order.setPayType(payType);
        voucherOrderService.updateById(order);

        log.info("创建支付订单：订单ID={}, 支付方式={}", orderId, payType);
        return Result.ok("支付订单创建成功");
    }

    @Override
    public Result simulatePaymentCallback(Long orderId) {
        return handlePaymentCallback(orderId);
    }

    @Override
    public Result wechatPayCallback(Long orderId) {
        log.info("收到微信支付回调：订单ID={}", orderId);
        return handlePaymentCallback(orderId);
    }

    @Override
    public Result alipayCallback(Long orderId) {
        log.info("收到支付宝支付回调：订单ID={}", orderId);
        return handlePaymentCallback(orderId);
    }

    private Result handlePaymentCallback(Long orderId) {
        String lockKey = ORDER_PAYMENT_LOCK_PREFIX + orderId;
        SimpleRedisLock lock = new SimpleRedisLock(lockKey, stringRedisTemplate);

        boolean isLock = lock.tryLock(0, 5, TimeUnit.SECONDS);
        if (!isLock) {
            log.warn("获取订单支付锁失败，订单ID={}", orderId);
            return Result.fail("订单处理中，请稍后重试");
        }

        try {
            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
                return Result.fail("订单不存在");
            }

            if (VoucherOrder.STATUS_PAID.equals(order.getStatus())) {
                log.info("订单已支付，订单ID={}", orderId);
                return Result.ok("订单已支付");
            }

            if (!VoucherOrder.STATUS_UNPAID.equals(order.getStatus())) {
                log.warn("订单状态不正确，无法支付，订单ID={}, 状态={}", orderId, order.getStatus());
                return Result.fail("订单状态不正确");
            }

            order.setStatus(VoucherOrder.STATUS_PAID);
            order.setPayTime(LocalDateTime.now());
            order.setUpdateTime(LocalDateTime.now());
            voucherOrderService.updateById(order);

            log.info("订单支付成功，订单ID={}", orderId);
            return Result.ok("支付成功");
        } catch (Exception e) {
            log.error("支付回调处理异常，订单ID={}", orderId, e);
            return Result.fail("支付回调处理失败");
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result checkPaymentStatus(Long orderId) {
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order != null) {
            return Result.ok(order.getStatus());
        }

        UserDTO user = UserHolder.getUser();
        String pendingUserId = stringRedisTemplate.opsForValue().get(SECKILL_ORDER_PENDING_KEY + orderId);
        if (!StringUtils.hasText(pendingUserId)) {
            return Result.fail("订单不存在");
        }
        if (user == null || user.getId() == null || !pendingUserId.equals(String.valueOf(user.getId()))) {
            return Result.fail("订单不属于当前用户");
        }
        return Result.ok(0);
    }
}