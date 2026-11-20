package com.example.mealcare.data.model;

import com.google.firebase.Timestamp;

import java.util.Map;

public class MedicationRecord {
    private String recordId; //기본키, 식별자
    private String patientUid; //외래키, 환자
    private String planId; //외래키, 복약 계획
    private String slotId;
    private int planVersion; //계획 버전
    private String scheduleDate; //환자 기준 날짜
    private String timeZone; //시간대
    private Timestamp scheduledAt; //복용 예정 시각
    private Map<String, Object> doseSnapshot; //당시 약품 ID·제품명·복용량·단위
    private String status; //taken, notTaken, voided
    private Timestamp takenAt; //복용시각
    private String createdByUid; //외래키, 최초 작성자
    private String updatedByUid; //외래키, 마지막 수정자
    private int revision; //기록 수정 버전
    private String note; //기록
    private Timestamp createdAt; //서버 저장 시각
    private Timestamp updatedAt; //마지막 수정시각

    public MedicationRecord() {};

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

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getSlotId() {
        return slotId;
    }

    public void setSlotId(String slotId) {
        this.slotId = slotId;
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

    public Map<String, Object> getDoseSnapshot() {
        return doseSnapshot;
    }

    public void setDoseSnapshot(Map<String, Object> doseSnapshot) {
        this.doseSnapshot = doseSnapshot;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Timestamp getTakenAt() {
        return takenAt;
    }

    public void setTakenAt(Timestamp takenAt) {
        this.takenAt = takenAt;
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
