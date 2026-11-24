package com.example.mealcare.data;

import com.example.mealcare.data.model.CareLink;
import com.example.mealcare.data.model.Permission;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Source;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.google.firebase.firestore.FirebaseFirestoreException.Code.DATA_LOSS;
import static com.google.firebase.firestore.FirebaseFirestoreException.Code.NOT_FOUND;
import static com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED;

/** 로그인한 회원의 환자·보호자 연결을 조회하고 관리합니다. */
public final class CareLinkRepository {
    private final FirebaseFirestore db;
    private final Supplier<String> currentUid;
    private final CareFunctions functions;

    public CareLinkRepository() {
        this(FirebaseFirestore.getInstance(), CurrentUser::authenticatedUid);
    }

    CareLinkRepository(FirebaseFirestore db, Supplier<String> currentUid) {
        this.db = db;
        this.currentUid = currentUid;
        this.functions = new CareFunctions(FirebaseFunctions.getInstance(db.getApp()), currentUid);
    }

    /**
     * 환자가 활성 연결의 세 권한을 한 번에 저장합니다. 지정하지 않은 값은 false입니다.
     * 기존 권한 하나만 바꿀 때는 조회한 연결의 Permission을 사용합니다.
     * <pre>{@code
     * Permission grants = link.getPermissions();
     * grants.setRecordMedication(false);
     * Task<Void> saved = links.updatePermissions(link.getLinkId(), grants);
     * }</pre>
     */
    public Task<Void> updatePermissions(String linkId, Permission permissions) {
        if (permissions == null) return Tasks.forException(new IllegalArgumentException("공유 권한이 필요합니다."));
        Map<String, Object> fields = new HashMap<>();
        fields.put("linkId", linkId);
        fields.put("permissions", CareFunctions.permissionFields(permissions));
        return functions.call("updatePermissions", fields).onSuccessTask(Runnable::run, result -> Tasks.forResult(null));
    }

    /**
     * 환자 또는 보호자가 자신의 연결을 해제합니다. 이미 해제된 연결에 다시 호출해도 이력은 한 건입니다.
     * <pre>{@code
     * Task<Void> revoked = links.revokeLink(link.getLinkId());
     * }</pre>
     */
    public Task<Void> revokeLink(String linkId) {
        return functions.call("revokeLink", Collections.singletonMap("linkId", linkId))
                .onSuccessTask(Runnable::run, result -> Tasks.forResult(null));
    }

    /** 환자 본인 또는 활성 연결에서 이 항목의 조회 권한을 받은 보호자인지 서버에서 확인합니다. */
    static Task<Void> checkReadAccess(DocumentReference patient, String actor, String permission) {
        if (actor == null) return Tasks.forException(new FirebaseFirestoreException("로그인이 필요합니다.", UNAUTHENTICATED));
        if (patient.getId().equals(actor)) return Tasks.forResult(null);
        // 식사 계획 자체는 환자 전용이고 readMeals는 식사 기록에 적용됩니다.
        if (permission == null) return Tasks.forException(new FirebaseFirestoreException(
                "환자 본인만 조회할 수 있습니다.", FirebaseFirestoreException.Code.PERMISSION_DENIED));
        return patient.getFirestore().collection("careLinks")
                .whereEqualTo("patientUid", patient.getId()).whereEqualTo("caregiverUid", actor)
                .whereEqualTo("status", "active").whereEqualTo("permissions." + permission, true)
                .limit(1).get(Source.SERVER).onSuccessTask(Runnable::run, links -> {
                    if (links.isEmpty()) throw new FirebaseFirestoreException("조회 권한이 없습니다.",
                            FirebaseFirestoreException.Code.PERMISSION_DENIED);
                    return Tasks.forResult(null);
                });
    }

    /**
     * 회원 역할과 활성 연결을 서버에서 조회합니다. 환자는 연결된 보호자 UID를,
     * 보호자는 연결된 환자 UID를 각 연결의 공유 권한과 함께 받을 수 있습니다.
     * <pre>{@code
     * new CareLinkRepository().getMyCareLinks().addOnSuccessListener(links -> {
     *     for (CareLink link : links) {
     *         // 보호자 기준 예시. 이 UID를 MealRecordRepository 생성자에 넘길 수 있습니다.
     *         String patientUid = link.getPatientUid();
     *         boolean canReadMeals = link.getPermissions().getReadMeals();
     *     }
     * });
     * }</pre>
     * @return 연결이 없으면 빈 목록입니다. 로그아웃하거나 조회 중 계정이 바뀌면 UNAUTHENTICATED로 실패합니다.
     */
    public Task<List<CareLink>> getMyCareLinks() {
        String uid = currentUid.get();
        if (uid == null) return Tasks.forException(new FirebaseFirestoreException("로그인이 필요합니다.", UNAUTHENTICATED));
        return db.collection("users").document(uid).get(Source.SERVER).onSuccessTask(Runnable::run, account -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            if (!account.exists()) throw new FirebaseFirestoreException("회원 정보가 없습니다.", NOT_FOUND);
            Object role = account.get("role");
            String memberField;
            if ("patient".equals(role)) memberField = "patientUid";
            else if ("caregiver".equals(role)) memberField = "caregiverUid";
            else throw new FirebaseFirestoreException("회원 역할을 확인할 수 없습니다.", DATA_LOSS);
            return db.collection("careLinks").whereEqualTo(memberField, uid)
                    .whereEqualTo("status", "active").get(Source.SERVER);
        }).onSuccessTask(Runnable::run, snapshot -> {
            CurrentUser.requireSameAccount(uid, currentUid);
            List<CareLink> links = new ArrayList<>(snapshot.size());
            for (DocumentSnapshot doc : snapshot) {
                CareLink link = doc.toObject(CareLink.class);
                if (link.getPatientUid() == null || link.getCaregiverUid() == null) {
                    throw new FirebaseFirestoreException("연결에 환자 또는 보호자 UID가 없습니다.", DATA_LOSS);
                }
                link.setLinkId(doc.getId());
                // 이전 문서에 빠진 권한은 false입니다. 대리 기록에는 복약 조회 권한도 필요합니다.
                Permission grants = link.getPermissions();
                if (grants == null) grants = new Permission();
                grants.setReadMeals(Boolean.TRUE.equals(grants.getReadMeals()));
                grants.setReadMedications(Boolean.TRUE.equals(grants.getReadMedications()));
                grants.setRecordMedication(grants.getReadMedications() && Boolean.TRUE.equals(grants.getRecordMedication()));
                link.setPermissions(grants);
                links.add(link);
            }
            return Tasks.forResult(links);
        });
    }
}
