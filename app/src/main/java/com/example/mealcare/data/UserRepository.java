package com.example.mealcare.data;

import com.example.mealcare.data.model.User;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.function.Supplier;

import static com.google.firebase.firestore.FirebaseFirestoreException.Code.DATA_LOSS;
import static com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND;
import static com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED;

/** 로그인한 회원의 계정 정보와 공개 프로필을 한 객체로 조회합니다. */
public final class UserRepository {
    private final FirebaseFirestore db;
    private final Supplier<String> currentUid;

    public UserRepository() {
        this(FirebaseFirestore.getInstance(), CurrentUser::authenticatedUid);
    }

    UserRepository(FirebaseFirestore db, Supplier<String> currentUid) {
        this.db = db;
        this.currentUid = currentUid;
    }

    /**
     * 같은 UID의 users, publicProfiles 문서를 온라인에서 한 트랜잭션으로 읽습니다.
     * <pre>{@code
     * new UserRepository().getCurrentUser()
     *         .addOnSuccessListener(user -> Log.d("회원", user.getNickname()))
     *         .addOnFailureListener(error -> Log.e("회원", "조회 실패", error));
     * }</pre>
     * @return 로그인한 회원. 프로필 사진이 없으면 profileImagePath는 null입니다.
     *         로그아웃이나 조회 중 계정 변경은 UNAUTHENTICATED, 회원 또는 프로필 문서 누락은 NOT_FOUND입니다.
     */
    public Task<User> getCurrentUser() {
        String uid = currentUid.get();
        if (uid == null) return Tasks.forException(new FirebaseFirestoreException("로그인이 필요합니다.", UNAUTHENTICATED));
        return db.runTransaction(tx -> {
            DocumentSnapshot account = tx.get(db.collection("users").document(uid));
            DocumentSnapshot profile = tx.get(db.collection("publicProfiles").document(uid));
            if (!account.exists() || !profile.exists()) {
                throw new FirebaseFirestoreException("회원 정보 또는 공개 프로필이 없습니다.", NOT_FOUND);
            }
            User user = new User();
            user.setUid(uid);
            user.setRole(account.getString("role"));
            user.setStatus(account.getString("status"));
            user.setCreatedAt(account.getTimestamp("createdAt"));
            user.setUpdatedAt(account.getTimestamp("updatedAt"));
            user.setNickname(profile.getString("nickname"));
            user.setProfileImagePath(profile.getString("profileImagePath"));
            if (user.getRole() == null || user.getStatus() == null || user.getCreatedAt() == null
                    || user.getUpdatedAt() == null || user.getNickname() == null) {
                throw new FirebaseFirestoreException("회원 필수 정보가 누락되었습니다.", DATA_LOSS);
            }
            return user;
        }).onSuccessTask(Runnable::run, user -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            return Tasks.forResult(user);
        });
    }
}
