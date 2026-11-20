package com.example.mealcare.data.model;
import com.google.firebase.Timestamp;

public class User {
    public String uid; //기본키, 유저 식별용
    public String role; //환자인가, 보호자인가?
    public String nickname; //커뮤니티 닉네임
    public String profileImagePath; //프로필 사진 경로
    public String status; // active, suspended, withdrawn
    public Timestamp createdAt; //가입 시각
    public Timestamp updatedAt; //수정 시각

    public User() {};

    public String getUid() {
        return uid;
    }

    public String getRole() {
        return role;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImagePath() {
        return profileImagePath;
    }

    public String getStatus() {
        return status;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public void setProfileImagePath(String profileImagePath) {
        this.profileImagePath = profileImagePath;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
