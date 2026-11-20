package com.example.mealcare.data.model;

import com.google.firebase.Timestamp;

import java.util.Map;

public class MealRecord {
    private String recordId; //기본키, 식별자
    private String patientUid; //외래키, 환자
    private String mealPlanId; //외래키, 식사 계획
    private int planVersion; //식사 버전
    private String scheduleDate; //환자 기준 날짜
    private String timeZone; //시간대
    private Timestamp scheduledAt; //식사 예정 시각
    private Map<String, Object> mealSnapshot; //식사 구분 이름
    private String status; //completed, skipped, voided
    private Timestamp completedAt; //식사 완료 시각
    private String createdByUid; //외래키, 최초 작성자
    private String updatedByUid; //외래키, 마지막 수정자
    private int revision; //버전
    private String note; //메모
    private Timestamp createdAt; //저장 시각
    private Timestamp updatedAt; //수정 시각

    public MealRecord() {};

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getPatientUid() {
        return patientUid;
    }

    public void setPatientUid(String patientUid) {
        this.patientUid = patientUid;
    }

    public String getMealPlanId() {
        return mealPlanId;
    }

    public void setMealPlanId(String mealPlanId) {
        this.mealPlanId = mealPlanId;
    }

    public int getPlanVersion() {
        return planVersion;
    }

    public void setPlanVersion(int planVersion) {
        this.planVersion = planVersion;
    }

    public String getScheduleDate() {
        return scheduleDate;
    }

    public void setScheduleDate(String scheduleDate) {
        this.scheduleDate = scheduleDate;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public Timestamp getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Timestamp scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public Map<String, Object> getMealSnapshot() {
        return mealSnapshot;
    }

    public void setMealSnapshot(Map<String, Object> mealSnapshot) {
        this.mealSnapshot = mealSnapshot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Timestamp getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Timestamp completedAt) {
        this.completedAt = completedAt;
    }

    public String getCreatedByUid() {
        return createdByUid;
    }

    public void setCreatedByUid(String createdByUid) {
        this.createdByUid = createdByUid;
    }

    public String getUpdatedByUid() {
        return updatedByUid;
    }

    public void setUpdatedByUid(String updatedByUid) {
        this.updatedByUid = updatedByUid;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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
