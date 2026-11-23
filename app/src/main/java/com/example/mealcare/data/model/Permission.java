package com.example.mealcare.data.model;

public class Permission {
    private Boolean readMeals; //식사 기록 조회
    private Boolean readMedications; //복약 계획 및 기록 조회
    private Boolean recordMedication; //복약 대리 기록

    public Permission() {};

    public Boolean getReadMeals() {
        return readMeals;
    }

    public void setReadMeals(Boolean readMeals) {
        this.readMeals = readMeals;
    }

    public Boolean getReadMedications() {
        return readMedications;
    }

    public void setReadMedications(Boolean readMedications) {
        this.readMedications = readMedications;
    }

    public Boolean getRecordMedication() {
        return recordMedication;
    }

    public void setRecordMedication(Boolean recordMedication) {
        this.recordMedication = recordMedication;
    }
}
