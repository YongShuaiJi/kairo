package com.example.demo;

import java.math.BigDecimal;

public class CreateOrderRequest {

    private String userId;
    private BigDecimal amount;

    public CreateOrderRequest() {
    }

    public CreateOrderRequest(String userId, BigDecimal amount) {
        this.userId = userId;
        this.amount = amount;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
