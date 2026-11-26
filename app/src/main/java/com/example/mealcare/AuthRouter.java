package com.example.mealcare;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.example.mealcare.data.UserRepository;
import com.google.firebase.auth.FirebaseAuth;

/**
 * 로그인된 사용자의 역할을 읽어서 환자 홈 / 보호자 홈으로 보내주는 도우미.
 * 로그인, 자동 로그인, 회원가입 직후에 공통으로 사용한다.
 */
public class AuthRouter {

    public static void goHome(Activity activity, String uid, Runnable onFail) {
        new UserRepository().getLoginRole(uid)
                .addOnSuccessListener(role -> {
                    Class<?> target;
                    if (UserRepository.ROLE_CAREGIVER.equals(role)) {
                        target = GuardianHomeActivity.class;
                    } else if (UserRepository.ROLE_PATIENT.equals(role)) {
                        target = PatientHomeActivity.class;
                    } else {
                        Toast.makeText(activity, "계정 정보를 찾을 수 없습니다. 다시 로그인해 주세요.",
                                Toast.LENGTH_SHORT).show();
                        FirebaseAuth.getInstance().signOut();
                        if (onFail != null) onFail.run();
                        return;
                    }
                    Intent intent = new Intent(activity, target);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    activity.startActivity(intent);
                    activity.finish();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(activity, "회원 정보 조회 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    if (onFail != null) onFail.run();
                });
    }

    /** 2.1 로그아웃: 로그아웃 후 로그인 화면으로 이동 (뒤로가기로 돌아올 수 없게 스택 비움) */
    public static void logout(Activity activity) {
        FirebaseAuth.getInstance().signOut();
        Intent intent = new Intent(activity, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }
}
