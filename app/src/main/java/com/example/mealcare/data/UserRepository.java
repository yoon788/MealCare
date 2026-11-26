package com.example.mealcare.data;

import com.example.mealcare.data.model.User;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static com.google.firebase.firestore.FirebaseFirestoreException.Code.ALREADY_EXISTS;
import static com.google.firebase.firestore.FirebaseFirestoreException.Code.DATA_LOSS;
import static com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND;
import static com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED;

/** 로그인한 회원의 계정 정보와 공개 프로필을 생성하고 조회합니다. */
public final class UserRepository {
    public static final String ROLE_PATIENT = "patient";
    public static final String ROLE_CAREGIVER = "caregiver";

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
     * Auth 가입 직후에 호출합니다. 같은 UID의 계정과 공개 프로필을 함께 생성합니다.
     * 같은 값으로 재시도해도 가입 시각, 상태, 프로필을 덮어쓰지 않습니다.
     * 예: repository.createProfile(account.getUid(), UserRepository.ROLE_CAREGIVER, "보호자 김민준");
     */
    public Task<Void> createProfile(String uid, String role, String nickname) {
        if (uid == null || !uid.equals(currentUid.get())) {
            return Tasks.forException(new FirebaseFirestoreException("가입한 계정으로 로그인해 주세요.", UNAUTHENTICATED));
        }
        if ((!ROLE_PATIENT.equals(role) && !ROLE_CAREGIVER.equals(role))
                || nickname == null || nickname.trim().isEmpty()) {
            return Tasks.forException(new IllegalArgumentException("계정 유형과 닉네임을 확인해 주세요."));
        }
        String name = nickname.trim();
        DocumentReference accountRef = db.collection("users").document(uid);
        DocumentReference profileRef = db.collection("publicProfiles").document(uid);
        return db.<Void>runTransaction(tx -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            DocumentSnapshot account = tx.get(accountRef);
            DocumentSnapshot profile = tx.get(profileRef);
            if (account.exists() || profile.exists()) {
                if (role.equals(account.getString("role")) && name.equals(profile.getString("nickname"))
                        && account.getTimestamp("createdAt") != null && account.getTimestamp("updatedAt") != null
                        && account.getString("status") != null) return null;
                throw new FirebaseFirestoreException("이미 등록된 회원 정보가 있습니다.", ALREADY_EXISTS);
            }
            Map<String, Object> fields = new HashMap<>();
            fields.put("role", role);
            fields.put("status", "active");
            fields.put("createdAt", FieldValue.serverTimestamp());
            fields.put("updatedAt", FieldValue.serverTimestamp());
            tx.set(accountRef, fields);
            tx.set(profileRef, Collections.singletonMap("nickname", name));
            return null;
        }).onSuccessTask(Runnable::run, unused -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            return Tasks.forResult(null);
        });
    }

    /** 홈 이동에 쓸 역할을 조회합니다. 이전 가입 양식의 회원은 본인 로그인 때 한 번 변환합니다. */
    public Task<String> getLoginRole(String uid) {
        if (uid == null || !uid.equals(currentUid.get())) {
            return Tasks.forException(new FirebaseFirestoreException("다시 로그인해 주세요.", UNAUTHENTICATED));
        }
        DocumentReference accountRef = db.collection("users").document(uid);
        return accountRef.get().onSuccessTask(Runnable::run, account -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            if (!account.exists()) throw new FirebaseFirestoreException("가입 정보를 먼저 등록해 주세요.", NOT_FOUND);
            String role = account.getString("role");
            boolean missingLegacyFields = account.contains("name")
                    && (!account.contains("status") || !account.contains("updatedAt"));
            if ("guardian".equals(role) || missingLegacyFields) return migrateLegacyAccount(accountRef);
            return Tasks.forResult(role);
        });
    }

    private Task<String> migrateLegacyAccount(DocumentReference accountRef) {
        String uid = accountRef.getId();
        return db.runTransaction(tx -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            DocumentSnapshot account = tx.get(accountRef);
            String oldRole = account.getString("role");
            String role = "guardian".equals(oldRole) ? ROLE_CAREGIVER : oldRole;
            if ((!ROLE_CAREGIVER.equals(role) && !ROLE_PATIENT.equals(role)) || account.getTimestamp("createdAt") == null) {
                throw new FirebaseFirestoreException("기존 회원 정보를 확인할 수 없습니다.", DATA_LOSS);
            }
            Map<String, Object> updates = new HashMap<>();
            if (!role.equals(oldRole)) updates.put("role", role);
            if (!account.contains("status")) updates.put("status", "active");
            if (!updates.isEmpty() || !account.contains("updatedAt")) {
                updates.put("updatedAt", FieldValue.serverTimestamp());
                tx.update(accountRef, updates);
            }
            return role;
        }).onSuccessTask(Runnable::run, role -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            return Tasks.forResult(role);
        });
    }

    /** 표시할 닉네임을 조회합니다. 아직 전환 전인 회원은 이전 가입 양식의 이름을 사용합니다. */
    public Task<String> getNickname(String uid) {
        return db.collection("publicProfiles").document(uid).get().onSuccessTask(Runnable::run, profile -> {
            String nickname = profile.getString("nickname");
            if (nickname != null) return Tasks.forResult(nickname);
            return db.collection("users").document(uid).get().onSuccessTask(Runnable::run, account -> {
                String name = account.getString("name");
                if (name == null) throw new FirebaseFirestoreException("회원 이름이 없습니다.", NOT_FOUND);
                return Tasks.forResult(name);
            });
        });
    }

    /**
     * 같은 UID의 users, publicProfiles 문서를 온라인에서 한 트랜잭션으로 읽습니다.
     * <pre>{@code
     * new UserRepository().getCurrentUser()
     *         .addOnSuccessListener(user -> Log.d("회원", user.getNickname()))
     *         .addOnFailureListener(error -> Log.e("회원", "조회 실패", error));
     * }</pre>
     * @return 로그인한 회원. 프로필 사진이 없으면 profileImagePath는 null입니다.
     *         로그아웃이나 조회 중 계정 변경은 UNAUTHENTICATED, 회원 또는 표시 이름을 담은 문서가 없으면 NOT_FOUND입니다.
     */
    public Task<User> getCurrentUser() {
        String uid = currentUid.get();
        if (uid == null) return Tasks.forException(new FirebaseFirestoreException("로그인이 필요합니다.", UNAUTHENTICATED));
        return db.runTransaction(tx -> {
            DocumentSnapshot account = tx.get(db.collection("users").document(uid));
            DocumentSnapshot profile = tx.get(db.collection("publicProfiles").document(uid));
            if (!account.exists() || (!profile.exists() && !account.contains("name"))) {
                throw new FirebaseFirestoreException("회원 정보 또는 공개 프로필이 없습니다.", NOT_FOUND);
            }
            User user = new User();
            user.setUid(uid);
            user.setRole(account.getString("role"));
            user.setStatus(account.getString("status"));
            user.setCreatedAt(account.getTimestamp("createdAt"));
            user.setUpdatedAt(account.getTimestamp("updatedAt"));
            // 이전 계정의 이름은 공개 프로필에 복사하지 않고 기존 문서에서 읽습니다.
            String nickname = profile.getString("nickname");
            user.setNickname(nickname != null ? nickname : account.getString("name"));
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
