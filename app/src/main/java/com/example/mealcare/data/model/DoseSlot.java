package com.example.mealcare.data.model;

import java.util.List;

public class DoseSlot {
    private String slotId;            //식별자
    private Double doseAmount;        //1회 복용량
    private String doseUnit;          //복용량 단위
    private List<Integer> daysOfWeek;  //날짜, 월요일 1 ~ 일요일 7
    private String timingType;        //"fixed" 또는 "mealRelative"
    private String localTime;         //고정 시각 예: "20:00"
    private String mealPlanId;        //식사 연동 대상
    private Integer offsetMinutes;    //복용 시간, 식전 30분은 -30, 식후 30분은 30

    public DoseSlot() {}

    public String getSlotId() {
        return slotId;
    }

    public void setSlotId(String slotId) {
        this.slotId = slotId;
    }

    public Double getDoseAmount() {
        return doseAmount;
    }

    public void setDoseAmount(Double doseAmount) {
        this.doseAmount = doseAmount;
    }

    public String getDoseUnit() {
        return doseUnit;
    }

    public void setDoseUnit(String doseUnit) {
        this.doseUnit = doseUnit;
    }

    public List<Integer> getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(List<Integer> daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public String getTimingType() {
        return timingType;
    }

    public void setTimingType(String timingType) {
        this.timingType = timingType;
    }

    public String getLocalTime() {
        return localTime;
    }

    public void setLocalTime(String localTime) {
        this.localTime = localTime;
    }

    public String getMealPlanId() {
        return mealPlanId;
    }

    public void setMealPlanId(String mealPlanId) {
        this.mealPlanId = mealPlanId;
    }

    public Integer getOffsetMinutes() {
        return offsetMinutes;
    }

    public void setOffsetMinutes(Integer offsetMinutes) {
        this.offsetMinutes = offsetMinutes;
    }
}