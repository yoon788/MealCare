package com.example.mealcare;

public class MealSlot {
    public static final String BREAKFAST = "BREAKFAST";
    public static final String LUNCH = "LUNCH";
    public static final String DINNER = "DINNER";

    public static String label(String slot) {
        switch (slot) {
            case BREAKFAST: return "아침";
            case LUNCH: return "점심";
            case DINNER: return "저녁";
            default: return "";
        }
    }

    // TODO: 팀에서 예정 식사 시간 저장 위치를 정하면 그 값으로 교체
    public static String plannedTime(String slot) {
        switch (slot) {
            case BREAKFAST: return "08:00";
            case LUNCH: return "12:00";
            case DINNER: return "18:30";
            default: return null;
        }
    }
}
