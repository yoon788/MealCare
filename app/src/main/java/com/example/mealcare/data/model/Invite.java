package com.example.mealcare.data.model;

import com.google.firebase.Timestamp;

import java.util.Map;

public class Invite {
    private String inviteId; //기본키, 초대코드 해시값
    private String patientUid; //외래키, 초대 발급 환자
    private Permission permissions; //권한
    private String status; //activate, used, revoked
    private Timestamp createdAt;
    private Timestamp expiresAt;
    private String usedByUid; //외래키, 초대 사용 보호자
    private Timestamp usedAt; //사용 성공 시각
    private Timestamp revokedAt; //사용 전 최소 시각

    public Invite() {};

    public String getInviteId() {
        return inviteId;
    }

    public void setInviteId(String inviteId) {
        this.inviteId = inviteId;
    }

    public String getPatientUid() {
        return patientUid;
    }

    public void setPatientUid(String patientUid) {
        this.patientUid = patientUid;
    }

    public Permission getPermissions() {
        return permissions;
    }

    public void setPermissions(Permission permissions) {
        this.permissions = permissions;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Timestamp expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getUsedByUid() {
        return usedByUid;
    }

    public void setUsedByUid(String usedByUid) {
        this.usedByUid = usedByUid;
    }

    public Timestamp getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Timestamp usedAt) {
        this.usedAt = usedAt;
    }

    public Timestamp getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Timestamp revokedAt) {
        this.revokedAt = revokedAt;
    }
}
