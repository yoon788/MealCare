package com.example.mealcare.data.model;

import com.google.firebase.Timestamp;

import java.util.List;
import java.util.Map;

public class MealPlan {
    private String mealPlanId; //식별자
    private String patientUid; //계획자
    private String mealType; //아침, 점심, 저녁, 기타
    private String mealName; //화면에 표시할 이름
    private List<MealScheduleEntry> weeklySchedule; //요일별 예정시각
    private String timeZone; //기준 시간대
    private String startDate; //적용 시작일
    private String endDate; //마지막 일
    private Map<String, Object> reminderSettings; //알림사용 여부, 예정시각 대비 시간차
    private String status; //activate, paused, stopped
    private int version; //버전
    private Timestamp createdAt; //생성 시각
    private Timestamp updatedAt; //수정 시각

    public MealPlan() {};

    public String getMealPlanId() {
        return mealPlanId;
    }

    public void setMealPlanId(String mealPlanId) {
        this.mealPlanId = mealPlanId;
    }

    public String getPatientUid() {
        return patientUid;
    }

    public void setPatientUid(String patientUid) {
        this.patientUid = patientUid;
    }

    public String getMealType() {
        return mealType;
    }

    public void setMealType(String mealType) {
        this.mealType = mealType;
    }

    public String getMealName() {
        return mealName;
    }

    public void setMealName(String mealName) {
        this.mealName = mealName;
    }

    public List<MealScheduleEntry> getWeeklySchedule() {
        return weeklySchedule;
    }

    public void setWeeklySchedule(List<MealScheduleEntry> weeklySchedule) {
        this.weeklySchedule = weeklySchedule;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public Map<String, Object> getReminderSettings() {
        return reminderSettings;
    }

    public void setReminderSettings(Map<String, Object> reminderSettings) {
        this.reminderSettings = reminderSettings;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}