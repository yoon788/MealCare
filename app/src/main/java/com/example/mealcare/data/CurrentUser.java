package com.example.mealcare.data;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.function.Supplier;

public class CurrentUser {
    // 로그인 기능이 붙기 전까지는 테스트용 uid로 저장
    public static String uid() {
        String uid = authenticatedUid();
        return uid != null ? uid : "test-patient";
    }

    /** 로그인한 Firebase 계정의 UID를 반환합니다. 로그아웃 상태이면 null입니다. */
    static String authenticatedUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    /** 비동기 조회 도중 로그인 계정이 바뀌면 이전 계정의 결과를 전달하지 않습니다. */
    static void requireSameAccount(String uid, Supplier<String> currentUid) throws FirebaseFirestoreException {
        if (!uid.equals(currentUid.get())) throw new FirebaseFirestoreException("로그인 계정이 변경되었습니다.",
                FirebaseFirestoreException.Code.UNAUTHENTICATED);
    }
}
