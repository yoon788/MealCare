package com.example.mealcare.data.model;

import com.google.firebase.Timestamp;

import java.util.List;
import java.util.Map;

public class MedicationPlans {
    private String planId; //기본키, 식별용
    private String patientUid; //계획자
    private String drugId; //약
    private String startDate; //시작 날짜
    private String endDate; //마지막 날짜
    private String timeZone; //기준 시간대
    private List<DoseSlot> doseSlots; //회차별 복용량
    private Map<String, Object> reminderSettings;
    private String status; //activate, paused, stopped
    private int version; //버전
    private Timestamp createdAt; //생성 시각
    private Timestamp updatedAt; //수정 시각

    public MedicationPlans() {}

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getPatientUid() {
        return patientUid;
    }

    public void setPatientUid(String patientUid) {
        this.patientUid = patientUid;
    }

    public String getDrugId() {
        return drugId;
    }

    public void setDrugId(String drugId) {
        this.drugId = drugId;
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

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public List<DoseSlot> getDoseSlots() {
        return doseSlots;
    }

    public void setDoseSlots(List<DoseSlot> doseSlots) {
        this.doseSlots = doseSlots;
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
