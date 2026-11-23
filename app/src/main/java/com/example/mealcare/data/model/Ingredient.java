package com.example.mealcare.data.model;

public class Ingredient {
    private String name;   // 성분명
    private Double amount; // 제품에 포함된 성분의 함량
    private String unit;   // 함량 단위. 예: "mg"
    private String basis;  // 함량의 기준. 예: "1정"

    public Ingredient() {}

    public String getName() {
        return name;
    }

    public Double getAmount() {
        return amount;
    }

    public String getUnit() {
        return unit;
    }

    public String getBasis() {
        return basis;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public void setBasis(String basis) {
        this.basis = basis;
    }
}