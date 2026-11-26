package com.example.mealcare.data;

import com.example.mealcare.data.model.Permission;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 초대와 연결 Repository가 사용하는 인증된 서버 함수 호출입니다. */
final class CareFunctions {
    private final FirebaseFunctions functions;
    private final Supplier<String> currentUid;

    CareFunctions(FirebaseFunctions functions, Supplier<String> currentUid) {
        this.functions = functions;
        this.currentUid = currentUid;
    }

    static Map<String, Boolean> permissionFields(Permission permissions) {
        Map<String, Boolean> grants = new HashMap<>();
        grants.put("readMeals", Boolean.TRUE.equals(permissions.getReadMeals()));
        grants.put("readMedications", Boolean.TRUE.equals(permissions.getReadMedications()));
        grants.put("recordMedication", Boolean.TRUE.equals(permissions.getRecordMedication()));
        return grants;
    }

    Task<Map<?, ?>> call(String name, Map<String, ?> fields) {
        String uid = currentUid.get();
        if (uid == null) return Tasks.forException(new IllegalStateException("로그인이 필요합니다."));
        Map<String, Object> data = new HashMap<>(fields);
        // 서버가 토큰에서 확인한 계정과 호출을 시작한 계정이 일치해야 합니다.
        data.put("expectedUid", uid);
        return functions.getHttpsCallable(name).call(data).onSuccessTask(Runnable::run, result -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            Object body = result.getData();
            if (!(body instanceof Map)) throw new IllegalStateException("서버의 응답 형식이 올바르지 않습니다.");
            return Tasks.forResult((Map<?, ?>) body);
        });
    }
}
