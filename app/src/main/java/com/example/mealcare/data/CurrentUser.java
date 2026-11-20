package com.example.mealcare.data;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class CurrentUser {
    // 로그인 기능이 붙기 전까지는 테스트용 uid로 저장
    public static String uid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : "test-patient";
    }
}
