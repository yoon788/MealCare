package com.example.mealcare.data;

import com.example.mealcare.data.model.Permission;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/** 환자와 보호자가 사용하는 일회용 초대입니다. 검증과 쓰기는 인증된 서버 함수에서 처리합니다. */
public final class InviteRepository {
    private final CareFunctions functions;

    public InviteRepository() {
        this(FirebaseFunctions.getInstance(), CurrentUser::authenticatedUid);
    }

    InviteRepository(FirebaseFunctions functions, Supplier<String> currentUid) {
        this.functions = new CareFunctions(functions, currentUid);
    }

    /** 발급한 환자가 받는 결과입니다. code는 보호자에게 전달하고 inviteId는 취소할 때 사용합니다. */
    public static final class IssuedInvite {
        public final String code;
        public final String inviteId;
        public final long expiresAtMillis;

        private IssuedInvite(Map<?, ?> result) {
            code = stringField(result, "code");
            inviteId = stringField(result, "inviteId");
            Object expiresAt = result.get("expiresAtMillis");
            if (!(expiresAt instanceof Number)) throw new IllegalStateException("초대 만료 시각이 누락되었습니다.");
            expiresAtMillis = ((Number) expiresAt).longValue();
        }
    }

    /**
     * 환자가 보호자 한 명에게 줄 초대를 발급합니다. 지정하지 않은 권한은 false입니다.
     * 발급 시 서버가 정한 만료 시각은 expiresAtMillis(Unix 밀리초)에 담깁니다. 현재 유효기간은 24시간입니다.
     * <pre>{@code
     * Permission grants = new Permission();
     * grants.setReadMeals(true);
     * Task<InviteRepository.IssuedInvite> issued = new InviteRepository().createInvite(grants);
     * // 성공 시 issued의 결과에 담긴 code를 보호자에게 전달합니다.
     * }</pre>
     */
    public Task<IssuedInvite> createInvite(Permission permissions) {
        if (permissions == null) return Tasks.forException(new IllegalArgumentException("공유 권한이 필요합니다."));
        return functions.call("createInvite", Collections.singletonMap("permissions", CareFunctions.permissionFields(permissions)))
                .onSuccessTask(Runnable::run, result -> Tasks.forResult(new IssuedInvite(result)));
    }

    /**
     * 보호자가 수락하면 활성 연결 ID를 반환합니다. 같은 보호자가 응답을 놓쳐 다시 요청해도 연결은 하나입니다.
     * 수락 요청은 보호자 계정당 10분에 5회까지이며 잘못 입력한 코드도 횟수에 포함됩니다.
     * <pre>{@code
     * Task<String> linkId = new InviteRepository().acceptInvite(code);
     * // 성공 후 new CareLinkRepository().getMyCareLinks()로 연결과 권한을 조회합니다.
     * }</pre>
     */
    public Task<String> acceptInvite(String code) {
        return functions.call("acceptInvite", Collections.singletonMap("code", code))
                .onSuccessTask(Runnable::run, result -> Tasks.forResult(stringField(result, "linkId")));
    }

    /** 발급한 환자가 미사용 초대를 취소합니다. 예: {@code invites.revokeInvite(issued.inviteId)} */
    public Task<Void> revokeInvite(String inviteId) {
        return functions.call("revokeInvite", Collections.singletonMap("inviteId", inviteId))
                .onSuccessTask(Runnable::run, result -> Tasks.forResult(null));
    }

    private static String stringField(Map<?, ?> result, String key) {
        Object value = result.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw new IllegalStateException("초대 응답 필드 누락: " + key);
        return (String) value;
    }
}
