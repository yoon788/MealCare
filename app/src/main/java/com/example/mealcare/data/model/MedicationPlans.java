package com.example.mealcare.data.model;

import com.example.mealcare.data.local.ReminderPlan;
import com.google.firebase.Timestamp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MedicationPlans {
    private String planId; //기본키, 식별용
    private String patientUid; //계획자
    private String sourceItemId; // 식약처 품목기준코드. 앞자리 0을 포함한 문자열로 보존합니다.
    private String drugName; // 등록 시 선택한 Drug.productName. 오프라인에서도 이 이름을 표시합니다.
    private String drugId; // 이전 계획에 저장된 앱 내부 약품 ID
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

    /** 등록 당시 제품명과 품목기준코드를 Room 알림 행에 복사합니다. */
    public ReminderPlan toReminderPlan() {
        return toReminderPlan(null);
    }

    /** 제품명이 없는 이전 계획에 한해 호출자가 전달한 이름을 사용합니다. */
    public ReminderPlan toReminderPlan(String legacyDrugName) {
        String identifier = sourceItemId != null ? sourceItemId : drugId;
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new IllegalArgumentException("복약 계획의 약품 식별자가 필요합니다.");
        }
        String name = sourceItemId == null && drugName == null ? legacyDrugName : drugName;
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("복약 계획에 등록 당시 제품명이 필요합니다.");
        }
        ReminderPlan row = new ReminderPlan(patientUid, planId);
        row.sourceItemId = sourceItemId;
        row.drugId = drugId;
        row.drugName = name;
        row.startDate = startDate;
        row.endDate = endDate;
        row.timeZone = timeZone;
        row.status = status;
        row.version = version;
        if (reminderSettings != null) {
            row.alarmEnabled = Boolean.TRUE.equals(reminderSettings.get("alarmEnabled"));
            row.repeatIntervalMinutes = reminderCount("repeatIntervalMinutes");
            row.maxRepeatCount = reminderCount("maxRepeatCount");
        }
        return row;
    }

    private int reminderCount(String key) {
        Object value = reminderSettings.get(key);
        if (value == null) return 0;
        if (!(value instanceof Number)) throw new IllegalArgumentException("잘못된 알림 설정: " + key);
        Number number = (Number) value;
        int count = number.intValue();
        if (count < 0 || number.doubleValue() != count) {
            throw new IllegalArgumentException("잘못된 알림 설정: " + key);
        }
        return count;
    }

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

    public String getSourceItemId() {
        return sourceItemId;
    }

    public void setSourceItemId(String sourceItemId) {
        this.sourceItemId = sourceItemId;
    }

    public String getDrugName() {
        return drugName;
    }

    public void setDrugName(String drugName) {
        this.drugName = drugName;
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

    /** Firestore 문서를 읽을 때 사용합니다. 계획을 만들 때는 {@link #configureReminder(boolean, int, int)}를 사용하세요. */
    public void setReminderSettings(Map<String, Object> reminderSettings) {
        this.reminderSettings = reminderSettings;
    }

    /**
     * 복약 계획에 알림 사용 여부, 반복 간격(분), 최대 반복 횟수를 설정합니다.
     * <pre>{@code
     * plan.configureReminder(true, 5, 2);  // 최초 알림 후 5분 간격으로 최대 2회 반복
     * }</pre>
     * 반복 없이 한 번만 알리려면 {@code plan.configureReminder(true, 0, 0)}을 사용하세요.
     * @throws IllegalArgumentException 반복 간격이나 횟수가 음수인 경우
     */
    public void configureReminder(boolean enabled, int repeatIntervalMinutes, int maxRepeatCount) {
        if (repeatIntervalMinutes < 0 || maxRepeatCount < 0) {
            throw new IllegalArgumentException("반복 간격과 최대 반복 횟수는 0 이상이어야 합니다.");
        }
        Map<String, Object> settings = new HashMap<>();
        settings.put("alarmEnabled", enabled);
        settings.put("repeatIntervalMinutes", (long) repeatIntervalMinutes);
        settings.put("maxRepeatCount", (long) maxRepeatCount);
        setReminderSettings(settings);
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
