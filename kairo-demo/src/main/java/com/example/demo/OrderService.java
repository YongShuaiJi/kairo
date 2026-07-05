package com.example.demo;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class OrderService {

    private final AtomicInteger createOrderInvocationCount = new AtomicInteger();
    private final AtomicInteger notificationInvocationCount = new AtomicInteger();

    public Order createOrder(CreateOrderRequest request) {
        createOrderInvocationCount.incrementAndGet();
        if (request.getAmount().compareTo(new BigDecimal("9999")) > 0) {
            throw new BizException("amount too large");
        }

        Order order = new Order();
        order.setId(UUID.randomUUID().toString());
        order.setStatus("SUCCESS");
        order.setAmount(request.getAmount());
        order.setMessage("origin");
        return order;
    }

    public int calculateScore(int base) {
        return base * 2;
    }

    public void sendNotification(String userId) {
        notificationInvocationCount.incrementAndGet();
        if ("boom".equals(userId)) {
            throw new BizException("notification failed");
        }
    }

    public static String staticMethod(String value) {
        return "origin-" + value;
    }

    public String overload(String value) {
        return "string-" + value;
    }

    public String overload(int value) {
        return "int-" + value;
    }

    public String callPrivateEcho(String value) {
        return privateEcho(value);
    }

    private String privateEcho(String value) {
        return "private-" + value;
    }

    public int createOrderInvocationCount() {
        return createOrderInvocationCount.get();
    }

    public int notificationInvocationCount() {
        return notificationInvocationCount.get();
    }
}
