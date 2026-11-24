package com.example.mealcare.data;

import android.util.Base64;

import com.example.mealcare.data.local.DailyMedicationDose;
import com.example.mealcare.data.model.MealPlan;
import com.example.mealcare.data.model.MealScheduleEntry;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.SuccessContinuation;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.Transaction;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.example.mealcare.data.CurrentUser.requireSameAccount;

/** 복약·식사 기록에 공통인 식별자와 예정 시각에 적용된 계획 버전을 판별합니다. */
final class RecordDocument {
    private RecordDocument() {}

    /** 양 끝 날짜를 포함해 한 쿼리로 읽습니다. */
    static <T> Task<List<T>> getRecords(DocumentReference patient, String collection, String readPermission,
                                        String start, String end, Supplier<String> currentUid, Function<DocumentSnapshot, T> toRecord) {
        return readRange(patient, collection, readPermission, start, end, currentUid, snapshot -> {
            List<T> result = new ArrayList<>(snapshot.size());
            for (DocumentSnapshot doc : snapshot) result.add(toRecord.apply(doc));
            return Tasks.forResult(result);
        });
    }

    static <T> Task<T> readRange(DocumentReference patient, String collection, String readPermission, String start, String end,
                                 Supplier<String> currentUid, SuccessContinuation<QuerySnapshot, T> read) {
        String actor = currentUid.get();
        if (actor == null) return Tasks.forException(new FirebaseFirestoreException("로그인이 필요합니다.",
                FirebaseFirestoreException.Code.UNAUTHENTICATED));
        try {
            DailyMedicationDose.weekdayOf(start);
            DailyMedicationDose.weekdayOf(end);
            if (start.compareTo(end) > 0) throw new IllegalArgumentException("시작일이 종료일보다 늦습니다.");
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }

        Task<Void> access = CareLinkRepository.checkReadAccess(patient, actor, readPermission);

        return access.onSuccessTask(Runnable::run, unused -> {
            requireSameAccount(actor, currentUid);
            Query query = start.equals(end) ? patient.collection(collection).whereEqualTo("scheduleDate", start)
                    : patient.collection(collection).whereGreaterThanOrEqualTo("scheduleDate", start)
                            .whereLessThanOrEqualTo("scheduleDate", end);
            return query.get(Source.SERVER);
        }).onSuccessTask(Runnable::run, snapshot -> {
            requireSameAccount(actor, currentUid);
            return read.then(snapshot);
        }).onSuccessTask(Runnable::run, result -> {
            requireSameAccount(actor, currentUid);
            return Tasks.forResult(result);
        });
    }

    /** 환자 본인은 연결 조회가 필요 없습니다. 보호자 연결은 쓰기 트랜잭션 안에서 다시 확인합니다. */
    static Task<DocumentReference> medicationWriterLink(DocumentReference patient, String actor) {
        if (patient.getId().equals(actor)) return Tasks.forResult(null);
        return patient.getFirestore().collection("careLinks").whereEqualTo("patientUid", patient.getId())
                .whereEqualTo("caregiverUid", actor).limit(1).get(Source.SERVER).onSuccessTask(Runnable::run, links -> {
                    if (links.isEmpty()) throw new FirebaseFirestoreException("환자와 연결된 보호자가 아닙니다.",
                            FirebaseFirestoreException.Code.PERMISSION_DENIED);
                    return Tasks.forResult(links.getDocuments().get(0).getReference());
                });
    }

    static void requireMedicationGrant(Transaction tx, DocumentReference link, String patientUid, String actor)
            throws FirebaseFirestoreException {
        DocumentSnapshot grant = tx.get(link);
        if (!patientUid.equals(grant.get("patientUid")) || !actor.equals(grant.get("caregiverUid"))
                || !"active".equals(grant.get("status")) || !Boolean.TRUE.equals(grant.get("permissions.recordMedication"))
                || !Boolean.TRUE.equals(grant.get("permissions.readMedications"))) {
            throw new FirebaseFirestoreException("이 환자의 복약 대리 기록 권한이 없습니다.", FirebaseFirestoreException.Code.PERMISSION_DENIED);
        }
    }

    /** 변경할 업무 필드만 구성합니다. 취소 시에는 서버에 저장된 메모를 보존합니다. */
    static Map<String, Object> resultChanges(String status, String timeField, Timestamp actualTime, String note) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", status);
        // Firestore에 저장되는 마이크로초 단위로 비교해 같은 시각을 다시 입력해도 이력이 늘지 않습니다.
        fields.put(timeField, actualTime == null ? null :
                new Timestamp(actualTime.getSeconds(), actualTime.getNanoseconds() / 1000 * 1000));
        if (!"voided".equals(status)) fields.put("note", note);
        return fields;
    }

    static Task<Integer> revise(CollectionReference records, String recordPatientUid, String id, int expectedRevision,
                                Map<String, Object> changes, Supplier<String> currentUid) {
        DocumentReference patient = records.getParent();
        String actor = currentUid.get();
        if (actor == null) return Tasks.forException(new FirebaseFirestoreException("로그인이 필요합니다.",
                FirebaseFirestoreException.Code.UNAUTHENTICATED));
        final DocumentReference ref;
        try {
            if (!patient.getId().equals(recordPatientUid)) throw new IllegalArgumentException("기록의 환자가 다릅니다.");
            if (expectedRevision < 1) throw new IllegalArgumentException("조회한 기록의 수정 버전이 필요합니다.");
            ref = records.document(PlanDocument.documentId(id, "기록 ID"));
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }
        boolean isCaregiver = !patient.getId().equals(actor);
        if (isCaregiver && !"medicationRecords".equals(records.getId())) {
            return Tasks.forException(new FirebaseFirestoreException("식사 기록은 환자 본인만 수정할 수 있습니다.",
                    FirebaseFirestoreException.Code.PERMISSION_DENIED));
        }
        Task<DocumentReference> access = isCaregiver ? medicationWriterLink(patient, actor) : Tasks.forResult(null);
        return access.onSuccessTask(Runnable::run, link -> ref.getFirestore().runTransaction(tx -> {
            requireSameAccount(actor, currentUid);
            if (link != null) requireMedicationGrant(tx, link, patient.getId(), actor);
            DocumentSnapshot current = tx.get(ref);
            if (!current.exists()) throw new FirebaseFirestoreException("수정할 기록이 없습니다.",
                    FirebaseFirestoreException.Code.NOT_FOUND);
            if (isCaregiver && !actor.equals(current.get("createdByUid"))) {
                throw new FirebaseFirestoreException("보호자는 본인이 작성한 복약 기록만 수정할 수 있습니다.",
                        FirebaseFirestoreException.Code.PERMISSION_DENIED);
            }
            // 트랜잭션이 재실행되더라도 호출자가 조회했던 revision과 비교합니다.
            if (!Long.valueOf(expectedRevision).equals(current.get("revision"))) {
                throw new FirebaseFirestoreException("기록이 변경되었습니다. 다시 조회한 뒤 수정하세요.",
                        FirebaseFirestoreException.Code.FAILED_PRECONDITION);
            }
            for (Map.Entry<String, Object> field : changes.entrySet()) {
                if (!Objects.equals(current.get(field.getKey()), field.getValue())) {
                    return writeRevision(tx, current, expectedRevision, actor, changes);
                }
            }
            return expectedRevision;
        }));
    }

    /** 수정 전 전체 문서와 변경자를 남기고, 같은 커밋에서 현재 결과와 수정 버전을 갱신합니다. */
    private static int writeRevision(Transaction tx, DocumentSnapshot current, int revision, String actor,
                                     Map<String, Object> changes) {
        int next = Math.addExact(revision, 1);
        DocumentReference ref = current.getReference();
        Map<String, Object> previous = new HashMap<>(current.getData());
        previous.put("supersededAt", FieldValue.serverTimestamp());
        previous.put("supersededByUid", actor);
        tx.set(ref.collection("revisions").document(String.valueOf(revision)), previous);
        Map<String, Object> update = new HashMap<>(changes);
        update.put("revision", (long) next);
        update.put("updatedByUid", actor);
        update.put("updatedAt", FieldValue.serverTimestamp());
        tx.update(ref, update);
        return next;
    }

    /** 각 부분의 바이트 길이도 해시해 구분자나 슬래시를 포함한 ID를 구분합니다. */
    static String recordId(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return "r-" + Base64.encodeToString(digest.digest(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean scheduledOn(DocumentSnapshot doc, String date) {
        String start = doc.getString("startDate");
        String end = doc.getString("endDate");
        return "active".equals(doc.get("status")) && start != null && start.compareTo(date) <= 0
                && (end == null || end.compareTo(date) >= 0);
    }

    // 첫 버전은 계획의 시작일부터, 이후 버전은 변경 커밋부터 다음 변경 커밋 직전까지 적용합니다.
    static boolean effectiveAt(DocumentSnapshot doc, long millis) {
        Timestamp due = new Timestamp(new Date(millis));
        Timestamp from = effectiveFrom(doc);
        Timestamp until = doc.getTimestamp("supersededAt");
        return (from == null || from.compareTo(due) <= 0) && (until == null || due.compareTo(until) < 0);
    }

    static Timestamp effectiveFrom(DocumentSnapshot doc) {
        Timestamp from = doc.getTimestamp("updatedAt");
        if (from == null || from.equals(doc.getTimestamp("createdAt")) || PlanDocument.versionOf(doc) == 0) return null;
        return from;
    }

    static DocumentSnapshot previousVersion(Transaction tx, DocumentReference root, DocumentSnapshot doc)
            throws FirebaseFirestoreException {
        int version = PlanDocument.versionOf(doc);
        if (version == 0 || (version == 1 && doc.getTimestamp("updatedAt") != null
                && doc.getTimestamp("updatedAt").equals(doc.getTimestamp("createdAt")))) return null;
        DocumentSnapshot previous = tx.get(root.collection("versions").document(String.valueOf(version - 1)));
        if (!previous.exists()) return null;
        if (PlanDocument.versionOf(previous) != version - 1 || previous.getTimestamp("supersededAt") == null) {
            throw new FirebaseFirestoreException("계획 버전 이력을 확인할 수 없습니다.",
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION);
        }
        return previous;
    }

    /** 한 식사 계획은 요일마다 예정 시각을 하나만 가집니다. 알림 사용 여부와 무관합니다. */
    static Long mealTime(DocumentSnapshot doc, String date, int weekday, String displayZone) {
        if (!scheduledOn(doc, date)) return null;
        MealPlan plan = doc.toObject(MealPlan.class);
        if (plan.getWeeklySchedule() == null) return null;
        for (MealScheduleEntry entry : plan.getWeeklySchedule()) {
            if (entry.getDaysOfWeek() == null || !entry.getDaysOfWeek().contains(weekday)) continue;
            Long time = DailyMedicationDose.scheduledMillis(date, entry.getLocalTime(), plan.getTimeZone(), 0, displayZone);
            if (time != null && effectiveAt(doc, time)) return time;
        }
        return null;
    }
}
