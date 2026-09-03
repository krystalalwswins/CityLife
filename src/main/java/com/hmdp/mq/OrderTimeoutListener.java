package com.hmdp.mq;

import com.hmdp.config.RocketMQConstants;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SimpleRedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.ORDER_TIMEOUT_LOCK_PREFIX;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        consumerGroup = RocketMQConstants.ORDER_TIMEOUT_CONSUMER_GROUP,
        topic = RocketMQConstants.ORDER_TIMEOUT_TOPIC,
        selectorExpression = RocketMQConstants.ORDER_TIMEOUT_TAG,
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class OrderTimeoutListener implements RocketMQListener<String> {

    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(String orderIdStr) {
        log.info("收到订单超时消息：订单ID={}", orderIdStr);
        
        try {
            Long orderId = Long.parseLong(orderIdStr);
            
            String lockKey = ORDER_TIMEOUT_LOCK_PREFIX + orderId;
            SimpleRedisLock lock = new SimpleRedisLock(lockKey, stringRedisTemplate);
            
            boolean isLock = lock.tryLock(0, 5, TimeUnit.SECONDS);
            if (!isLock) {
                log.warn("获取订单超时处理锁失败，订单ID={}", orderId);
                return;
            }
            
            try {
                voucherOrderService.closeTimeoutOrder(orderId);
            } finally {
                lock.unlock();
            }
            
        } catch (Exception e) {
            log.error("订单超时处理异常，订单ID={}", orderIdStr, e);
            throw new IllegalStateException("订单超时处理失败，订单ID=" + orderIdStr, e);
        }
    }
}
