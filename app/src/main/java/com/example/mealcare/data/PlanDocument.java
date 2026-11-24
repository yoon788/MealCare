package com.example.mealcare.data;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.Transaction;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.function.Supplier;

/** 식사·복약 계획의 공통 필드, 버전 이력과 재시도 규칙입니다. */
final class PlanDocument {
    private static final List<String> EDITABLE_STATUSES = Arrays.asList("active", "paused");

    private PlanDocument() {}

    /** 서버에서 수정 가능한 계획만 조회합니다. 두 조회 모두 같은 상태 기준과 모델 변환을 사용합니다. */
    static <T> Task<List<T>> getPlans(CollectionReference collection, String grant, Supplier<String> currentUid,
                                      Function<DocumentSnapshot, T> toPlan) {
        return read(collection, grant, currentUid, () -> collection.whereIn("status", EDITABLE_STATUSES).get(Source.SERVER))
                .onSuccessTask(Runnable::run, snapshot -> {
                    List<T> plans = new ArrayList<>(snapshot.size());
                    for (DocumentSnapshot doc : snapshot) plans.add(toPlan.apply(doc));
                    return Tasks.forResult(plans);
                });
    }

    static <T> Task<T> getPlan(CollectionReference collection, String id, String grant, Supplier<String> currentUid,
                                Function<DocumentSnapshot, T> toPlan) {
        final DocumentReference ref;
        try {
            ref = collection.document(documentId(id, "계획 ID"));
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }
        return read(collection, grant, currentUid, () -> ref.get(Source.SERVER)).onSuccessTask(Runnable::run, doc -> {
            if (!doc.exists() || !EDITABLE_STATUSES.contains(doc.get("status"))) {
                throw new FirebaseFirestoreException("조회할 수 있는 활성·일시정지 계획이 없습니다.",
                        FirebaseFirestoreException.Code.NOT_FOUND);
            }
            return Tasks.forResult(toPlan.apply(doc));
        });
    }

    private static <T> Task<T> read(CollectionReference collection, String grant, Supplier<String> currentUid,
                                    Supplier<Task<T>> fetch) {
        String actor = currentUid.get();
        return CareLinkRepository.checkReadAccess(collection.getParent(), actor, grant)
                .onSuccessTask(Runnable::run, unused -> {
                    CurrentUser.requireSameAccount(actor, currentUid);
                    return fetch.get();
                }).onSuccessTask(Runnable::run, value -> {
                    CurrentUser.requireSameAccount(actor, currentUid);
                    return Tasks.forResult(value);
                });
    }

    static Map<String, Object> scheduleFields(String startDate, String endDate, String timeZone, String status) {
        String start = date(startDate, "시작일");
        if (endDate != null && date(endDate, "종료일").compareTo(start) < 0) {
            throw new IllegalArgumentException("종료일은 시작일 이후이거나 같은 날이어야 합니다.");
        }
        String zone = requiredText(timeZone, "시간대");
        if (android.icu.util.TimeZone.getCanonicalID(zone) == null) {
            throw new IllegalArgumentException("알 수 없는 시간대입니다: " + zone);
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("startDate", start);
        fields.put("endDate", endDate);
        fields.put("timeZone", zone);
        if (!EDITABLE_STATUSES.contains(status)) {
            throw new IllegalArgumentException("등록·수정할 계획 상태는 active 또는 paused여야 합니다. 중단은 deletePlan을 사용하세요.");
        }
        fields.put("status", status);
        return fields;
    }

    /** 같은 내용이면 기존 서버 시각을 보존하고, 다른 내용이면 기존 문서를 보호합니다. */
    static void checkRetry(DocumentSnapshot existing, Map<String, Object> fields, String conflictMessage)
            throws FirebaseFirestoreException {
        if (!matches(existing, fields)) {
            throw new FirebaseFirestoreException(conflictMessage, FirebaseFirestoreException.Code.ALREADY_EXISTS);
        }
    }

    private static boolean matches(DocumentSnapshot existing, Map<String, Object> fields) {
        for (Map.Entry<String, Object> field : fields.entrySet()) {
            if (!Objects.equals(field.getValue(), existing.get(field.getKey()))) return false;
        }
        return true;
    }

    static DocumentSnapshot readForUpdate(Transaction tx, DocumentReference ref) throws FirebaseFirestoreException {
        DocumentSnapshot existing = tx.get(ref);
        if (!existing.exists() || "stopped".equals(existing.get("status"))) {
            throw new FirebaseFirestoreException("수정할 계획이 없거나 이미 중단되었습니다.",
                    FirebaseFirestoreException.Code.NOT_FOUND);
        }
        return existing;
    }

    /** 수정 전 읽었던 버전을 비교합니다. 동일한 요청의 재시도에는 이력을 추가하지 않습니다. */
    static int update(Transaction tx, DocumentSnapshot existing, int expectedVersion, Map<String, Object> fields)
            throws FirebaseFirestoreException {
        int version = versionOf(existing);
        if (version != expectedVersion) {
            if (expectedVersion >= 0 && (long) expectedVersion + 1 == version && matches(existing, fields)) return version;
            throw new FirebaseFirestoreException("다른 변경이 먼저 저장되었습니다. 최신 계획을 다시 읽어 주세요.",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION);
        }
        if (matches(existing, fields)) return version;
        return writeRevision(tx, existing, fields, version);
    }

    /** 중단된 문서는 참조용으로 남기며 이미 삭제된 ID에 대한 재요청도 성공합니다. */
    static Task<Void> delete(CollectionReference collection, String id) {
        final DocumentReference ref;
        try {
            ref = collection.document(documentId(id, "계획 ID"));
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }
        return ref.getFirestore().runTransaction(tx -> {
            DocumentSnapshot existing = tx.get(ref);
            if (existing.exists() && !"stopped".equals(existing.get("status"))) {
                Map<String, Object> changes = new HashMap<>();
                changes.put("status", "stopped");
                writeRevision(tx, existing, changes, versionOf(existing));
            }
            return null;
        });
    }

    static int versionOf(DocumentSnapshot existing) {
        Object value = existing.get("version");
        if (value == null) return 0; // 이전 문서의 기본값은 Java 계획 모델과 같습니다.
        if (!(value instanceof Long) || (long) value < 0 || (long) value > Integer.MAX_VALUE) {
            throw new IllegalStateException("저장된 계획 버전이 유효하지 않습니다.");
        }
        return ((Long) value).intValue();
    }

    /** 기존 문서의 updatedAt부터 이번 커밋(versions/{version}.supersededAt)까지가 이전 버전의 적용 기간입니다. */
    private static int writeRevision(Transaction tx, DocumentSnapshot existing, Map<String, Object> fields, int version) {
        int next = Math.addExact(version, 1);
        DocumentReference ref = existing.getReference();
        Map<String, Object> previous = new HashMap<>(existing.getData());
        previous.put("supersededAt", FieldValue.serverTimestamp());
        tx.set(ref.collection("versions").document(String.valueOf(version)), previous);
        Map<String, Object> changes = new HashMap<>(fields);
        changes.put("version", (long) next);
        changes.put("updatedAt", FieldValue.serverTimestamp());
        tx.update(ref, changes);
        return next;
    }

    static Map<String, Object> withServerTimestamps(Map<String, Object> fields) {
        Map<String, Object> document = new HashMap<>(fields);
        document.put("createdAt", FieldValue.serverTimestamp());
        document.put("updatedAt", FieldValue.serverTimestamp());
        return document;
    }

    private static String date(String value, String label) {
        String text = requiredText(value, label);
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException(label + "은 YYYY-MM-DD 형식이어야 합니다.");
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            format.parse(text);
        } catch (ParseException error) {
            throw new IllegalArgumentException(label + "에 유효한 날짜를 입력하세요.", error);
        }
        return text;
    }

    static String documentId(String value, String label) {
        String id = requiredText(value, label);
        if (id.contains("/") || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException(label + "에 유효한 문서 ID가 필요합니다.");
        }
        return id;
    }

    static String requiredText(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " 값이 필요합니다.");
        return value;
    }
}
