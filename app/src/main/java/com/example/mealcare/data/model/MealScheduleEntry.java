package com.example.mealcare.data.model;

import java.util.List;

public class MealScheduleEntry {
    private List<Integer> daysOfWeek;
    private String localTime;

    public MealScheduleEntry() {}

    public List<Integer> getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(List<Integer> daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public String getLocalTime() {
        return localTime;
    }

    public void setLocalTime(String localTime) {
        this.localTime = localTime;
    }
}
