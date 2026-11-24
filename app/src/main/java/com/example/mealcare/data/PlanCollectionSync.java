package com.example.mealcare.data;

import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.mealcare.data.local.ReminderDao;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/** 환자별 계획 컬렉션 하나를 구독합니다. 식사·복약 구독은 각자 서버 목록을 확인합니다. */
abstract class PlanCollectionSync {
    private static final String TAG = "PlanCollectionSync";
    private final FirebaseFirestore firestore;
    final ReminderDao reminders;
    private final Executor worker;
    private final String collection;
    // 아래 구독 상태와 Room 쓰기는 모두 같은 작업 스레드에서 순서대로 처리합니다.
    private String patientUid;
    private ListenerRegistration registration;
    private boolean needsFullSnapshot;

    /** worker에는 구독 전환과 스냅샷 처리를 직렬화하는 백그라운드 실행기를 전달합니다. */
    PlanCollectionSync(FirebaseFirestore firestore, ReminderDao reminders, Executor worker, String collection) {
        this.firestore = firestore;
        this.reminders = reminders;
        this.worker = worker;
        this.collection = collection;
    }

    abstract List<String> localPlanIds(String uid);
    abstract void savePlan(String uid, QueryDocumentSnapshot doc);
    abstract void deletePlan(String uid, String planId);

    /** 같은 환자의 중복 구독을 막고, 로그아웃(null) 시 이전 환자의 구독을 해제합니다. */
    public final void watchPatient(@Nullable String uid) {
        worker.execute(() -> {
            if (Objects.equals(patientUid, uid)) return;
            if (registration != null) {
                registration.remove();
                registration = null;
            }
            patientUid = uid;
            needsFullSnapshot = true;
            if (uid != null) {
                registration = firestore.collection("patients").document(uid).collection(collection)
                        .addSnapshotListener(worker, MetadataChanges.INCLUDE,
                                (snapshot, error) -> onSnapshot(uid, snapshot, error));
            }
        });
    }

    private void onSnapshot(String uid, @Nullable QuerySnapshot snapshot,
                            @Nullable FirebaseFirestoreException error) {
        if (!uid.equals(patientUid)) return;
        if (error != null) {
            Log.w(TAG, collection + " 구독 종료: " + error.getCode());
            return;
        }
        if (snapshot == null || snapshot.getMetadata().isFromCache() || snapshot.getMetadata().hasPendingWrites()) {
            // 캐시가 비었거나 서버 승인을 기다리는 동안은 기존 오프라인 일정을 보존합니다.
            // 메타데이터만 바뀌어도 다음 서버 스냅샷을 받아 건너뛴 변경을 모두 반영합니다.
            needsFullSnapshot = true;
            return;
        }
        try {
            if (needsFullSnapshot) {
                Set<String> localOnly = new HashSet<>(localPlanIds(uid));
                for (QueryDocumentSnapshot doc : snapshot) {
                    localOnly.remove(doc.getId());
                    save(uid, doc);
                }
                // 앱이 꺼진 동안 삭제된 문서는 새 구독에 REMOVED 이벤트가 오지 않습니다.
                for (String id : localOnly) deletePlan(uid, id);
                needsFullSnapshot = false;
            } else {
                for (DocumentChange change : snapshot.getDocumentChanges()) {
                    if (change.getType() == DocumentChange.Type.REMOVED) {
                        deletePlan(uid, change.getDocument().getId());
                    } else {
                        save(uid, change.getDocument());
                    }
                }
            }
        } catch (SQLiteException errorWritingRoom) {
            needsFullSnapshot = true;
            Log.e(TAG, "Room 쓰기 실패. 다음 서버 목록과 다시 대조합니다.", errorWritingRoom);
        }
    }

    private void save(String uid, QueryDocumentSnapshot doc) {
        // 중단된 계획은 서버에서 참조·이력용으로 보관하고 로컬 일정에서 제거합니다.
        if ("stopped".equals(doc.get("status"))) {
            deletePlan(uid, doc.getId());
            return;
        }
        try {
            savePlan(uid, doc);
        } catch (SQLiteConstraintException invalidPlan) {
            Log.w(TAG, collection + " 문서의 제약 조건 오류. 기존 일정을 보존합니다.", invalidPlan);
        } catch (SQLiteException storageError) {
            throw storageError;
        } catch (RuntimeException invalidPlan) {
            // 변환할 수 없는 문서 한 건 때문에 나머지 계획의 추가·수정·삭제가 막히지 않게 합니다.
            Log.w(TAG, collection + " 문서를 Room 형식으로 변환할 수 없습니다.", invalidPlan);
        }
    }
}
