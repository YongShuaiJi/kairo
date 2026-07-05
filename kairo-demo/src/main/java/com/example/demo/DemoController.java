package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/demo")
public class DemoController {

    private final OrderService orderService;

    public DemoController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public Order createOrder(@RequestBody CreateOrderRequest request) {
        return orderService.createOrder(request);
    }

    @GetMapping("/score")
    public Map<String, Object> calculateScore(@RequestParam int base) {
        return Map.of("base", base, "score", orderService.calculateScore(base));
    }

    @PostMapping("/notifications/{userId}")
    public Map<String, Object> sendNotification(@PathVariable String userId) {
        orderService.sendNotification(userId);
        return Map.of("userId", userId, "sent", true);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "createOrderInvocationCount", orderService.createOrderInvocationCount(),
                "notificationInvocationCount", orderService.notificationInvocationCount()
        );
    }
}
