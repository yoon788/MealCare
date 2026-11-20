package com.example.mealcare.data.model;

import com.google.firebase.Timestamp;

import java.util.Map;

public class CareLink {
    private String linkId; //식별자
    private String patientUid; //외래키, 환자
    private String caregiverUid; //외래키, 보호자
    private String status; //activate or revoked

    public void setPermissions(Permission permissions) {
        this.permissions = permissions;
    }

    private Permission permissions; //연결별 공유 권한
    private Timestamp revokedAt; //연결 해제 시각
    private String revokedByUid; //외래키, 해제 요청 회원
    private Timestamp createdAt; //최초 연결 시각
    private Timestamp updatedAt; //상태 권한 수정 시각

    public CareLink() {};

    public String getLinkId() {
        return linkId;
    }

    public void setLinkId(String linkId) {
        this.linkId = linkId;
    }

    public String getPatientUid() {
        return patientUid;
    }

    public void setPatientUid(String patientUid) {
        this.patientUid = patientUid;
    }

    public String getCaregiverUid() {
        return caregiverUid;
    }

    public void setCaregiverUid(String caregiverUid) {
        this.caregiverUid = caregiverUid;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Permission getPermissions() {
        return permissions;
    }

    public Timestamp getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Timestamp revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokedByUid() {
        return revokedByUid;
    }

    public void setRevokedByUid(String revokedByUid) {
        this.revokedByUid = revokedByUid;
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
