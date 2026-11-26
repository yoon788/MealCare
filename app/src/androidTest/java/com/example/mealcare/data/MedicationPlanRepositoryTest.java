package com.example.mealcare.data;

import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.mealcare.data.model.CareLink;
import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.Drug;
import com.example.mealcare.data.model.MealPlan;
import com.example.mealcare.data.model.MealRecord;
import com.example.mealcare.data.model.MealScheduleEntry;
import com.example.mealcare.data.model.MedicationPlans;
import com.example.mealcare.data.model.MedicationRecord;
import com.example.mealcare.data.model.User;
import com.example.mealcare.data.local.ReminderDao;
import com.example.mealcare.data.local.ReminderDatabase;
import com.example.mealcare.data.local.ReminderDoseSlot;
import com.example.mealcare.data.local.ReminderMealTime;
import com.example.mealcare.data.local.ReminderPlan;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

/** adb reverse로 Firestore 포트를 연결한 후 계측 인수 firestoreEmulatorPort로 해당 포트를 지정합니다. */
@RunWith(AndroidJUnit4.class)
public class MedicationPlanRepositoryTest {
    private FirebaseApp app;
    private FirebaseFirestore db;
    private DocumentReference patient;
    private MedicationPlanRepository repository;
    private MedicationPlans plan;
    private DoseSlot slot;
    private Drug drug;
    private FirebaseApp readerApp;
    private FirebaseFirestore reader;
    private ReminderDatabase room;
    private ExecutorService worker;
    private MedicationPlanSync sync;
    private MealPlanSync mealSync;

    @Before public void setUp() {
        String port = InstrumentationRegistry.getArguments().getString("firestoreEmulatorPort");
        assumeNotNull(port);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id = UUID.randomUUID().toString();
        FirebaseOptions options = new FirebaseOptions.Builder().setProjectId("demo-mealcare")
                .setApplicationId("1:1234567890:android:1234567890").setApiKey("emulator-only").build();
        app = FirebaseApp.initializeApp(context, options, id);
        db = FirebaseFirestore.getInstance(app);
        db.useEmulator("127.0.0.1", Integer.parseInt(port));
        patient = db.collection("patients").document(id);
        repository = new MedicationPlanRepository(db, id, () -> id);
        drug = new Drug();
        drug.setSourceItemId("001234567");
        drug.setProductName("테스트 약");
        slot = new DoseSlot();
        slot.setSlotId("morning");
        slot.setDoseAmount(0.5);
        slot.setDoseUnit("정");
        slot.setDaysOfWeek(Arrays.asList(1, 7));
        slot.setTimingType("fixed");
        slot.setLocalTime("08:30");
        plan = new MedicationPlans();
        plan.setStartDate("2026-11-23");
        plan.setTimeZone("Asia/Seoul");
        plan.setDoseSlots(Collections.singletonList(slot));
        plan.configureReminder(true, 5, 2);
    }

    @After public void tearDown() throws Exception {
        if (readerApp != null) {
            closeLocalMirror();
            worker.shutdown();
            await(reader.terminate());
            readerApp.delete();
            context().deleteDatabase(patient.getId());
        }
        if (db != null) await(db.terminate());
        if (app != null) app.delete();
    }

    /** 별도 Firebase 클라이언트가 구독하므로 다른 기기에서 변경하는 경우도 검증합니다. */
    private void openLocalMirror() {
        if (readerApp == null) {
            worker = Executors.newSingleThreadExecutor();
            readerApp = FirebaseApp.initializeApp(context(), app.getOptions(), "reader-" + patient.getId());
            reader = FirebaseFirestore.getInstance(readerApp);
            reader.useEmulator("127.0.0.1", Integer.parseInt(
                    InstrumentationRegistry.getArguments().getString("firestoreEmulatorPort")));
        }
        room = Room.databaseBuilder(context(), ReminderDatabase.class, patient.getId()).build();
        sync = new MedicationPlanSync(reader, room.reminders(), worker);
        mealSync = new MealPlanSync(reader, room.reminders(), worker);
        sync.watchPatient(patient.getId());
        mealSync.watchPatient(patient.getId());
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private void closeLocalMirror() throws Exception {
        if (room == null) return;
        sync.watchPatient(null);
        mealSync.watchPatient(null);
        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        room.close();
        room = null;
    }

    @Test public void remoteCreatesEditsAndDeletesReachRoom() throws Exception {
        openLocalMirror();
        ReminderDao dao = room.reminders();
        String uid = patient.getId();
        String id = await(repository.createPlan(plan, drug));
        waitFor(() -> !dao.getEnabledPlans(uid).isEmpty());
        ReminderPlan localPlan = dao.getEnabledPlans(uid).get(0);
        assertEquals("001234567", localPlan.sourceItemId);
        assertEquals(5, localPlan.repeatIntervalMinutes);
        assertEquals(2, localPlan.maxRepeatCount);
        assertEquals("morning", dao.getSlots(uid, id).get(0).slotId);

        DocumentReference ref = patient.collection("medicationPlans").document(id);
        slot.setSlotId("evening");
        slot.setLocalTime("20:00");
        // 문서 속성의 키가 잘못돼도 다른 환자의 Room 행을 바꿀 수 없습니다.
        await(ref.update("doseSlots", Collections.singletonList(slot), "status", "paused",
                "patientUid", "other", "planId", "wrong-id"));
        waitFor(() -> dao.getEnabledPlans(uid).isEmpty());
        List<ReminderDoseSlot> slots = dao.getSlots(uid, id);
        assertEquals(1, slots.size());
        assertEquals("evening", slots.get(0).slotId);
        assertEquals("20:00", slots.get(0).localTime);
        assertTrue(dao.getMedicationPlanIds("other").isEmpty());

        await(ref.delete());
        waitFor(() -> dao.getMedicationPlanIds(uid).isEmpty());
        assertTrue(dao.getSlots(uid, id).isEmpty());
    }

    @Test public void remoteMealChangesReplaceWeekdaysAndDeleteOnlyThatPatientsPlan() throws Exception {
        openLocalMirror();
        String uid = patient.getId();
        ReminderDao dao = room.reminders();
        MealPlan meal = breakfastPlan();
        String id = await(new MealPlanRepository(db, uid).createPlan(meal));
        waitFor(() -> dao.getMealTime(uid, id, 7) != null);
        assertEquals("08:00", dao.getMealTime(uid, id, 1).localTime);
        assertEquals("09:00", dao.getMealTime(uid, id, 7).localTime);
        assertEquals(Collections.singletonList(id), dao.getMealPlanIds(uid));

        // 같은 ID를 가진 다른 환자의 일정을 미리 저장해 갱신·삭제 범위를 확인합니다.
        meal.setPatientUid("other");
        dao.saveMealPlan(meal);
        MealScheduleEntry tuesday = new MealScheduleEntry();
        tuesday.setDaysOfWeek(Collections.singletonList(2));
        tuesday.setLocalTime("10:15");
        DocumentReference ref = patient.collection("mealPlans").document(id);
        await(ref.update("weeklySchedule", Collections.singletonList(tuesday), "status", "paused",
                "timeZone", "Asia/Tokyo", "startDate", "2026-12-01", "endDate", "2026-12-31",
                "patientUid", "other", "mealPlanId", "wrong-id"));
        waitFor(() -> dao.getMealTime(uid, id, 7) == null);
        assertNull(dao.getMealTime(uid, id, 1));
        ReminderMealTime row = dao.getMealTime(uid, id, 2);
        assertNotNull(row);
        assertEquals("10:15", row.localTime);
        assertEquals("Asia/Tokyo", row.timeZone);
        assertEquals("2026-12-01", row.startDate);
        assertEquals("2026-12-31", row.endDate);
        assertEquals("paused", row.status);
        assertNull(dao.getMealTime("other", "wrong-id", 2));

        await(ref.delete());
        waitFor(() -> dao.getMealPlanIds(uid).isEmpty());
        assertNull(dao.getMealTime(uid, id, 2));
        assertEquals("09:00", dao.getMealTime("other", id, 7).localTime);
    }

    @Test public void reopeningOfflinePreservesRowsUntilServerConfirmsDeletion() throws Exception {
        openLocalMirror();
        String uid = patient.getId();
        String id = await(repository.createPlan(plan, drug));
        MealPlan breakfast = breakfastPlan();
        String mealId = await(new MealPlanRepository(db, uid).createPlan(breakfast));
        waitFor(() -> room.reminders().getMedicationPlanIds(uid).contains(id));
        waitFor(() -> room.reminders().getMealTime(uid, mealId, 1) != null);
        closeLocalMirror(); // 앱 종료처럼 구독을 끊고 실제 Room 파일을 닫습니다.
        await(repository.deletePlan(id));
        await(new MealPlanRepository(db, uid).deletePlan(mealId));
        await(reader.disableNetwork());

        // 서버에 없는 두 행을 준비해 캐시만 받은 경우와 다른 환자 범위를 확인합니다.
        room = Room.databaseBuilder(context(), ReminderDatabase.class, uid).build();
        plan.setPlanId("room-only");
        plan.setStatus("paused");
        room.reminders().saveMedicationPlan(plan);
        plan.setPatientUid("other");
        plan.setPlanId(id);
        room.reminders().saveMedicationPlan(plan);
        breakfast.setMealPlanId("room-only");
        room.reminders().saveMealPlan(breakfast);
        breakfast.setPatientUid("other");
        breakfast.setMealPlanId(mealId);
        room.reminders().saveMealPlan(breakfast);
        room.close();
        openLocalMirror();

        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        CountDownLatch cachedMeds = new CountDownLatch(1);
        CountDownLatch cachedMeals = new CountDownLatch(1);
        ListenerRegistration medProbe = onCachedSnapshot(uid, "medicationPlans", cachedMeds);
        ListenerRegistration mealProbe = onCachedSnapshot(uid, "mealPlans", cachedMeals);
        try {
            assertTrue(cachedMeds.await(10, TimeUnit.SECONDS));
            assertTrue(cachedMeals.await(10, TimeUnit.SECONDS));
            assertEquals(2, room.reminders().getMedicationPlanIds(uid).size());
            assertEquals(1, room.reminders().getSlots(uid, id).size());
            assertEquals(2, room.reminders().getMealPlanIds(uid).size());
            assertEquals("08:00", room.reminders().getMealTime(uid, mealId, 1).localTime);
            await(reader.enableNetwork());
            waitFor(() -> room.reminders().getMedicationPlanIds(uid).isEmpty());
            waitFor(() -> room.reminders().getMealPlanIds(uid).isEmpty());
            assertTrue(room.reminders().getSlots(uid, id).isEmpty());
            assertEquals(Collections.singletonList(id), room.reminders().getMedicationPlanIds("other"));
            assertEquals("09:00", room.reminders().getMealTime("other", mealId, 7).localTime);
        } finally {
            medProbe.remove();
            mealProbe.remove();
        }
    }

    private ListenerRegistration onCachedSnapshot(String uid, String collection, CountDownLatch cached) {
        return reader.collection("patients").document(uid).collection(collection)
                .addSnapshotListener(worker, MetadataChanges.INCLUDE, (snapshot, error) -> {
                    if (snapshot != null && snapshot.getMetadata().isFromCache()) cached.countDown();
                });
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Room 반영 시간 초과");
            Thread.sleep(50);
        }
    }

    @Test public void concurrentCreatesAndRetryPreserveOneServerDocument() throws Exception {
        Task<String> first = repository.createPlan(plan, drug);
        Task<String> second = repository.createPlan(plan, drug);
        slot.setLocalTime("09:00"); // 비동기 요청이 원래 일정의 사본을 사용하는지 확인
        String id = await(first);
        assertEquals(id, await(second));
        assertEquals(id, plan.getPlanId());
        assertEquals(drug.getSourceItemId(), plan.getSourceItemId());
        assertEquals(drug.getProductName(), plan.getDrugName());
        DocumentReference ref = patient.collection("medicationPlans").document(id);
        DocumentSnapshot saved = await(ref.get(Source.SERVER));
        assertEquals(1, await(patient.collection("medicationPlans").get(Source.SERVER)).size());
        assertNotNull(saved.getTimestamp("createdAt"));
        assertEquals(saved.getTimestamp("createdAt"), saved.getTimestamp("updatedAt"));
        assertFalse(saved.contains("drugId"));
        assertEquals(Boolean.TRUE, saved.getBoolean("reminderSettings.alarmEnabled"));
        assertEquals(Long.valueOf(5), saved.getLong("reminderSettings.repeatIntervalMinutes"));
        assertEquals(Long.valueOf(2), saved.getLong("reminderSettings.maxRepeatCount"));
        MedicationPlans stored = saved.toObject(MedicationPlans.class);
        assertNotNull(stored);
        assertEquals("001234567", stored.getSourceItemId());
        assertEquals("테스트 약", stored.getDrugName());
        assertEquals(patient.getId(), stored.getPatientUid());
        assertEquals("active", stored.getStatus());
        assertEquals(1, stored.getVersion());
        assertEquals("08:30", stored.getDoseSlots().get(0).getLocalTime());
        assertEquals(Arrays.asList(1, 7), stored.getDoseSlots().get(0).getDaysOfWeek());
        assertEquals(FirebaseFirestoreException.Code.ALREADY_EXISTS,
                firestoreError(repository.createPlan(plan, drug)));
        slot.setLocalTime("08:30");
        assertEquals(id, await(repository.createPlan(plan, drug)));
        assertEquals(saved.getData(), await(ref.get(Source.SERVER)).getData());
    }

    @Test public void mealRelativePlanResolvesMealWithinPatient() throws Exception {
        slot.setTimingType("mealRelative");
        slot.setLocalTime(null);
        slot.setMealPlanId("breakfast");
        slot.setOffsetMinutes(-30);
        await(db.collection("patients").document("other").collection("mealPlans").document("breakfast")
                .set(Collections.singletonMap("mealName", "아침")));
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(repository.createPlan(plan, drug)));
        String id = plan.getPlanId();
        assertFalse(await(patient.collection("medicationPlans").document(id).get(Source.SERVER)).exists());
        MealPlan breakfast = breakfastPlan();
        breakfast.setMealPlanId("breakfast");
        await(new MealPlanRepository(db, patient.getId()).createPlan(breakfast));
        assertEquals(id, await(repository.createPlan(plan, drug)));
        MedicationPlans stored = await(patient.collection("medicationPlans").document(id).get(Source.SERVER))
                .toObject(MedicationPlans.class);
        assertEquals(Integer.valueOf(-30), stored.getDoseSlots().get(0).getOffsetMinutes());
    }

    @Test public void invalidInputFailsBeforeReservingOrWritingAPlan() throws Exception {
        plan.setEndDate("2026-02-30");
        assertTrue(repository.createPlan(plan, drug).getException() instanceof IllegalArgumentException);
        plan.setEndDate(null);
        slot.setDoseAmount(0.0);
        assertTrue(repository.createPlan(plan, drug).getException() instanceof IllegalArgumentException);
        slot.setDoseAmount(1.0);
        plan.setPatientUid("another-patient");
        assertTrue(repository.createPlan(plan, drug).getException() instanceof IllegalArgumentException);
        assertNull(plan.getPlanId());
        assertTrue(await(patient.collection("medicationPlans").get(Source.SERVER)).isEmpty());
    }

    private static MealPlan breakfastPlan() {
        MealScheduleEntry weekdays = new MealScheduleEntry();
        weekdays.setDaysOfWeek(Arrays.asList(1, 2, 3, 4, 5));
        weekdays.setLocalTime("08:00");
        MealScheduleEntry weekend = new MealScheduleEntry();
        weekend.setDaysOfWeek(Arrays.asList(6, 7));
        weekend.setLocalTime("09:00");
        MealPlan meal = new MealPlan();
        meal.setMealType("아침");
        meal.setMealName("아침 식사");
        meal.setWeeklySchedule(Arrays.asList(weekdays, weekend));
        meal.setStartDate("2026-11-23");
        meal.setTimeZone("Asia/Seoul");
        meal.configureReminder(true, -10);
        return meal;
    }

    @Test public void mealCreationPersistsScheduleAndRetriesWithoutOverwriting() throws Exception {
        MealPlanRepository meals = new MealPlanRepository(db, patient.getId());
        MealPlan meal = breakfastPlan();
        Task<String> first = meals.createPlan(meal);
        Task<String> second = meals.createPlan(meal);
        meal.getWeeklySchedule().get(0).setLocalTime("07:00");
        String id = await(first);
        assertEquals(id, await(second));
        assertEquals(id, meal.getMealPlanId());
        assertEquals(1, await(patient.collection("mealPlans").get(Source.SERVER)).size());
        DocumentReference ref = patient.collection("mealPlans").document(id);
        DocumentSnapshot snapshot = await(ref.get(Source.SERVER));
        MealPlan stored = snapshot.toObject(MealPlan.class);
        assertNotNull(stored);
        assertEquals(id, stored.getMealPlanId());
        assertEquals(patient.getId(), stored.getPatientUid());
        assertEquals("BREAKFAST", stored.getMealType());
        assertEquals("아침 식사", stored.getMealName());
        assertEquals("08:00", stored.getWeeklySchedule().get(0).getLocalTime());
        assertEquals(Arrays.asList(6, 7), stored.getWeeklySchedule().get(1).getDaysOfWeek());
        assertEquals(Boolean.TRUE, stored.getReminderSettings().get("alarmEnabled"));
        assertEquals(-10L, stored.getReminderSettings().get("offsetMinutes"));
        assertEquals("active", stored.getStatus());
        assertEquals(1, stored.getVersion());
        assertNotNull(stored.getCreatedAt());
        assertEquals(stored.getCreatedAt(), stored.getUpdatedAt());
        assertEquals(FirebaseFirestoreException.Code.ALREADY_EXISTS, firestoreError(meals.createPlan(meal)));
        assertEquals(id, await(meals.createPlan(stored)));
        assertEquals(snapshot.getData(), await(ref.get(Source.SERVER)).getData());
    }

    @Test public void invalidMealOrMismatchedPatientWritesNothing() throws Exception {
        MealPlanRepository meals = new MealPlanRepository(db, patient.getId());
        MealPlan meal = breakfastPlan();
        meal.getWeeklySchedule().get(1).setDaysOfWeek(Arrays.asList(5, 6));
        assertTrue(meals.createPlan(meal).getException() instanceof IllegalArgumentException);
        meal.getWeeklySchedule().get(1).setDaysOfWeek(Arrays.asList(6, 7));
        meal.setEndDate("2026-11-22");
        assertTrue(meals.createPlan(meal).getException() instanceof IllegalArgumentException);
        meal.setEndDate(null);
        meal.setPatientUid("another-patient");
        assertTrue(meals.createPlan(meal).getException() instanceof IllegalArgumentException);
        assertNull(meal.getMealPlanId());
        assertTrue(await(patient.collection("mealPlans").get(Source.SERVER)).isEmpty());
    }

    private static <T> T await(Task<T> task) throws Exception {
        return Tasks.await(task, 30, TimeUnit.SECONDS);
    }

    @Test public void medicationEditsArchiveOnceAndStaleEditsCannotRestoreDeletedPlan() throws Exception {
        String id = await(repository.createPlan(plan, drug));
        DocumentReference ref = patient.collection("medicationPlans").document(id);
        DocumentSnapshot original = await(ref.get(Source.SERVER));
        MedicationPlans stale = original.toObject(MedicationPlans.class);
        plan.setEndDate("2026-12-31");
        slot.setDoseAmount(2.0);
        Task<Void> first = repository.updatePlan(plan);
        Task<Void> retry = repository.updatePlan(plan);
        await(first);
        await(retry);
        DocumentSnapshot updated = await(ref.get(Source.SERVER));
        assertEquals(2, plan.getVersion());
        assertEquals(Long.valueOf(2), updated.getLong("version"));
        assertEquals(original.getTimestamp("createdAt"), updated.getTimestamp("createdAt"));
        DocumentSnapshot previous = await(ref.collection("versions").document("1").get(Source.SERVER));
        assertEquals(original.get("doseSlots"), previous.get("doseSlots"));
        assertEquals(updated.getTimestamp("updatedAt"), previous.getTimestamp("supersededAt"));
        stale.setStatus("paused");
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(repository.updatePlan(stale)));
        plan.setSourceItemId("다른 약");
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(repository.updatePlan(plan)));
        plan.setSourceItemId(drug.getSourceItemId());
        assertEquals(updated.getData(), await(ref.get(Source.SERVER)).getData());

        await(repository.deletePlan(id));
        await(repository.deletePlan(id));
        DocumentSnapshot stopped = await(ref.get(Source.SERVER));
        assertEquals("stopped", stopped.getString("status"));
        assertEquals(Long.valueOf(3), stopped.getLong("version"));
        assertEquals(2, await(ref.collection("versions").get(Source.SERVER)).size());
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(repository.updatePlan(plan)));
        assertEquals(FirebaseFirestoreException.Code.ALREADY_EXISTS, firestoreError(repository.createPlan(plan, drug)));
        assertTrue(repository.deletePlan("bad/id").getException() instanceof IllegalArgumentException);
    }

    @Test public void updatesPreserveLegacyDrugIdentityAndStayWithinPatient() throws Exception {
        String id = await(repository.createPlan(plan, drug));
        DocumentReference ref = patient.collection("medicationPlans").document(id);
        await(ref.update("version", FieldValue.delete(), "drugName", FieldValue.delete(),
                "sourceItemId", FieldValue.delete(), "drugId", "legacy-drug", "extraField", "preserved",
                "patientUid", FieldValue.delete(), "planId", FieldValue.delete()));
        MedicationPlans legacy = await(repository.getPlan(id));
        legacy.setStatus("paused");
        MedicationPlanRepository other = new MedicationPlanRepository(db, patient.getId() + "-other", () -> patient.getId() + "-other");
        assertTrue(other.updatePlan(legacy).getException() instanceof IllegalArgumentException);
        await(other.deletePlan(id));
        await(repository.updatePlan(legacy));
        DocumentSnapshot saved = await(ref.get(Source.SERVER));
        assertEquals("paused", saved.getString("status"));
        assertEquals("legacy-drug", saved.getString("drugId"));
        assertEquals("preserved", saved.getString("extraField"));
        assertFalse(saved.contains("sourceItemId"));
        assertEquals(1, legacy.getVersion());
        assertTrue(await(ref.collection("versions").document("0").get(Source.SERVER)).exists());
    }

    @Test public void mealEditsRecalculateLinkedDosesAndDeletionKeepsTheirReference() throws Exception {
        openLocalMirror();
        String uid = patient.getId();
        ReminderDao dao = room.reminders();
        MealPlanRepository meals = new MealPlanRepository(db, uid, () -> uid);
        MealPlan breakfast = breakfastPlan();
        String mealId = await(meals.createPlan(breakfast));
        String planId = await(repository.createPlan(plan, drug));
        slot.setTimingType("mealRelative");
        slot.setMealPlanId(mealId);
        slot.setOffsetMinutes(30);
        await(repository.updatePlan(plan));
        DocumentReference mealRef = patient.collection("mealPlans").document(mealId);
        MealPlan stale = await(meals.getPlan(mealId));
        breakfast.getWeeklySchedule().get(0).setLocalTime("09:15");
        await(meals.updatePlan(breakfast));
        await(meals.updatePlan(breakfast));
        assertEquals(2, breakfast.getVersion());
        assertEquals(1, await(mealRef.collection("versions").get(Source.SERVER)).size());
        stale.setMealName("다른 식사");
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(meals.updatePlan(stale)));
        waitFor(() -> !dao.getSlots(uid, planId).isEmpty()
                && "mealRelative".equals(dao.getSlots(uid, planId).get(0).timingType)
                && dao.getMealTime(uid, mealId, 1) != null
                && "09:15".equals(dao.getMealTime(uid, mealId, 1).localTime));
        assertEquals("2026-11-23 09:45", dao.getDailyMedicationSchedule(uid, "2026-11-23").get(0).scheduledLocalDateTime);

        await(meals.deletePlan(mealId));
        await(meals.deletePlan(mealId));
        assertEquals(Long.valueOf(3), await(mealRef.get(Source.SERVER)).getLong("version"));
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(meals.updatePlan(breakfast)));
        waitFor(() -> dao.getMealTime(uid, mealId, 1) == null);
        assertEquals(mealId, dao.getSlots(uid, planId).get(0).mealPlanId);
        assertNull(dao.getDailyMedicationSchedule(uid, "2026-11-23").get(0).scheduledAtMillis);
        plan.configureReminder(true, 0, 0);
        await(repository.updatePlan(plan)); // 이미 연결된 회차의 다른 속성은 계속 수정할 수 있습니다.
        slot.setSlotId("new-slot");
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(repository.updatePlan(plan)));
        await(repository.deletePlan(planId));
        waitFor(() -> dao.getSlots(uid, planId).isEmpty());
    }

    @Test public void planReadsFilterStatusAndReturnEditablePatientScopedModels() throws Exception {
        String uid = patient.getId();
        MealPlanRepository meals = new MealPlanRepository(db, uid, () -> uid);
        String medId = await(repository.createPlan(plan, drug));
        String mealId = await(meals.createPlan(breakfastPlan()));
        // 이전 문서에 잘못된 ID가 있어도 읽고 쓰는 대상은 실제 문서 경로입니다.
        await(patient.collection("mealPlans").document(mealId).update("patientUid", "wrong", "mealPlanId", "wrong"));
        MedicationPlans med = await(repository.getPlan(medId));
        MealPlan meal = await(meals.getPlan(mealId));
        assertEquals(medId, med.getPlanId());
        assertEquals(mealId, meal.getMealPlanId());
        assertEquals(uid, meal.getPatientUid());
        assertEquals(medId, await(repository.getPlans()).get(0).getPlanId());
        assertEquals(mealId, await(meals.getPlans()).get(0).getMealPlanId());

        med.setStatus("paused");
        med.getDoseSlots().get(0).setDoseAmount(2.0);
        meal.setStatus("paused");
        meal.getWeeklySchedule().get(0).setLocalTime("08:30");
        await(repository.updatePlan(med));
        await(meals.updatePlan(meal));
        assertEquals("paused", await(repository.getPlans()).get(0).getStatus());
        assertEquals("paused", await(meals.getPlans()).get(0).getStatus());
        MedicationPlans editedMed = await(repository.getPlan(medId));
        MealPlan editedMeal = await(meals.getPlan(mealId));
        assertEquals(2, editedMed.getVersion());
        assertEquals(2.0, editedMed.getDoseSlots().get(0).getDoseAmount(), 0.0);
        assertEquals(2, editedMeal.getVersion());
        assertEquals("08:30", editedMeal.getWeeklySchedule().get(0).getLocalTime());

        MedicationPlanRepository otherMeds = new MedicationPlanRepository(db, uid + "-other", () -> uid + "-other");
        MealPlanRepository otherMeals = new MealPlanRepository(db, uid + "-other", () -> uid + "-other");
        assertTrue(await(otherMeds.getPlans()).isEmpty());
        assertTrue(await(otherMeals.getPlans()).isEmpty());
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(otherMeds.getPlan(medId)));
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(otherMeals.getPlan(mealId)));
        assertTrue(repository.getPlan("bad/id").getException() instanceof IllegalArgumentException);
        assertTrue(meals.getPlan(null).getException() instanceof IllegalArgumentException);

        await(repository.deletePlan(medId));
        await(meals.deletePlan(mealId));
        assertTrue(await(repository.getPlans()).isEmpty());
        assertTrue(await(meals.getPlans()).isEmpty());
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(repository.getPlan(medId)));
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(meals.getPlan(mealId)));
    }

    @Test public void recordingTheSameDoseFromTwoClientsCreatesOneImmutableResult() throws Exception {
        openLocalMirror(); // 독립 Firebase 클라이언트로 동시 저장합니다.
        String uid = patient.getId();
        MedicationRecordRepository records = new MedicationRecordRepository(db, uid, () -> uid);
        MedicationRecordRepository secondDevice = new MedicationRecordRepository(reader, uid, () -> uid);
        slot.setSlotId("아침/1");
        String planId = await(repository.createPlan(plan, drug));
        String date = "2026-11-23";
        Task<String> first = records.saveRecord(planId, "아침/1", date, true);
        Task<String> second = secondDevice.saveRecord(planId, "아침/1", date, true);
        String id = await(first);
        assertEquals(id, await(second));
        CollectionReference collection = patient.collection("medicationRecords");
        DocumentReference ref = collection.document(id);
        DocumentSnapshot saved = await(ref.get(Source.SERVER));
        MedicationRecord entry = saved.toObject(MedicationRecord.class);
        assertEquals(id, entry.getRecordId());
        assertEquals(uid, entry.getPatientUid());
        assertEquals(planId, entry.getPlanId());
        assertEquals("아침/1", entry.getSlotId());
        assertEquals("taken", entry.getStatus());
        assertNull(entry.getTakenAt());
        assertEquals(date, entry.getScheduleDate());
        assertEquals("Asia/Seoul", entry.getTimeZone());
        assertEquals(1795390200000L, entry.getScheduledAt().toDate().getTime()); // 월요일 서울 08:30
        assertEquals("001234567", entry.getDoseSnapshot().get("sourceItemId"));
        assertEquals("테스트 약", entry.getDoseSnapshot().get("drugName"));
        assertEquals(0.5, entry.getDoseSnapshot().get("doseAmount"));
        assertEquals("정", entry.getDoseSnapshot().get("doseUnit"));
        assertEquals(1, entry.getPlanVersion());
        assertEquals(1, entry.getRevision());
        assertEquals(uid, entry.getCreatedByUid());
        assertEquals(uid, entry.getUpdatedByUid());
        assertNotNull(entry.getCreatedAt());
        assertEquals(entry.getCreatedAt(), entry.getUpdatedAt());
        assertEquals(FirebaseFirestoreException.Code.ALREADY_EXISTS, firestoreError(records.saveRecord(planId, "아침/1", date, false)));
        assertEquals(1, await(collection.get(Source.SERVER)).size());
        String sunday = await(records.saveRecord(planId, "아침/1", "2026-11-29", false));
        assertNotEquals(id, sunday);
        assertEquals("notTaken", await(collection.document(sunday).get(Source.SERVER)).getString("status"));
        await(repository.deletePlan(planId));
        assertEquals(id, await(records.saveRecord(planId, "아침/1", date, true)));
        assertEquals(saved.getData(), await(ref.get(Source.SERVER)).getData());
    }

    @Test public void recordUsesHistoricalPlanAndMealTimesAcrossMidnight() throws Exception {
        String uid = patient.getId();
        MealPlan meal = breakfastPlan();
        meal.getWeeklySchedule().get(0).setLocalTime("23:50");
        String mealId = await(new MealPlanRepository(db, uid).createPlan(meal));
        slot.setTimingType("mealRelative");
        slot.setMealPlanId(mealId);
        slot.setOffsetMinutes(30);
        plan.configureReminder(false, 0, 0);
        String id = await(repository.createPlan(plan, drug));
        // 변경 시각을 고정해 테스트 실행 시점과 관계없이 과거 버전을 재현합니다.
        Timestamp cutoff = new Timestamp(1795478400L, 0); // 화요일 09:00 서울
        for (DocumentReference root : Arrays.asList(patient.collection("medicationPlans").document(id),
                patient.collection("mealPlans").document(mealId))) {
            Map<String, Object> original = await(root.get(Source.SERVER)).getData();
            original.put("supersededAt", cutoff);
            await(root.collection("versions").document("1").set(original));
            await(root.update("updatedAt", cutoff, "version", 2L, "status", "stopped"));
        }
        MedicationRecordRepository records = new MedicationRecordRepository(db, uid, () -> uid);
        assertCounts(await(records.getStatistics("2026-11-23", "2026-11-29")), 1, 0, 0, 1);
        String recordId = await(records.saveRecord(id, "morning", "2026-11-23", true));
        MedicationRecord record = await(patient.collection("medicationRecords").document(recordId).get(Source.SERVER))
                .toObject(MedicationRecord.class);
        assertEquals("2026-11-23", record.getScheduleDate());
        assertEquals(1795447200000L, record.getScheduledAt().toDate().getTime()); // 화요일 서울 00:20
        assertEquals(1, record.getPlanVersion());
        assertEquals(recordId, await(records.getRecords("2026-11-23")).get(0).getRecordId());
        assertTrue(await(records.getRecords("2026-11-24")).isEmpty());
        assertCounts(await(records.getStatistics("2026-11-23", "2026-11-29")), 1, 1, 0, 0);
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(records.saveRecord(id, "morning", "2026-11-30", true)));
    }

    @Test public void recordingRequiresScheduledDoseAndCurrentCaregiverGrant() throws Exception {
        String uid = patient.getId();
        String id = await(repository.createPlan(plan, drug));
        String date = "2026-11-23";
        String caregiver = uid + "-care";
        MedicationRecordRepository records = new MedicationRecordRepository(db, uid, () -> caregiver);
        MedicationRecordRepository signedOut = new MedicationRecordRepository(db, uid, () -> null);
        assertEquals(FirebaseFirestoreException.Code.UNAUTHENTICATED, firestoreError(signedOut.saveRecord(id, "morning", date, false)));
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(records.saveRecord(id, "morning", date, false)));
        DocumentReference link = db.collection("careLinks").document();
        await(link.set(Collections.singletonMap("patientUid", uid)));
        await(link.update("caregiverUid", caregiver, "status", "active", "permissions.recordMedication", true, "permissions.readMedications", true));
        assertTrue(records.saveRecord(id, "morning", "2026-02-30", true).getException() instanceof IllegalArgumentException);
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(records.saveRecord(id, "morning", "2026-11-24", true)));
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(records.saveRecord(id, "absent", date, true)));
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(records.saveRecord("absent", "morning", date, true)));
        assertTrue(await(patient.collection("medicationRecords").get(Source.SERVER)).isEmpty());
        String recordId = await(records.saveRecord(id, "morning", date, false));
        assertEquals(caregiver, await(patient.collection("medicationRecords").document(recordId).get(Source.SERVER)).getString("createdByUid"));
        await(link.update("permissions.recordMedication", false));
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(records.saveRecord(id, "morning", date, false)));
    }

    @Test public void sameMealFromTwoClientsKeepsOneRecordPerPlanAndDate() throws Exception {
        openLocalMirror();
        String uid = patient.getId();
        MealPlanRepository plans = new MealPlanRepository(db, uid);
        String id = await(plans.createPlan(breakfastPlan()));
        MealRecordRepository records = new MealRecordRepository(db, uid, () -> uid);
        MealRecordRepository secondDevice = new MealRecordRepository(reader, uid, () -> uid);
        CollectionReference collection = patient.collection("mealRecords");
        String date = "2026-11-23";
        Task<String> first = records.saveRecord(id, date, true);
        Task<String> second = secondDevice.saveRecord(id, date, true);
        String recordId = await(first);
        assertEquals(recordId, await(second));
        assertEquals(1, await(collection.get(Source.SERVER)).size());
        DocumentReference ref = collection.document(recordId);
        DocumentSnapshot saved = await(ref.get(Source.SERVER));
        MealRecord entry = saved.toObject(MealRecord.class);
        assertEquals(recordId, entry.getRecordId());
        assertEquals(uid, entry.getPatientUid());
        assertEquals(id, entry.getMealPlanId());
        assertEquals(date, entry.getScheduleDate());
        assertEquals("completed", entry.getStatus());
        assertEquals("BREAKFAST", entry.getMealSnapshot().get("mealType"));
        assertEquals("아침 식사", entry.getMealSnapshot().get("mealName"));
        assertEquals(1795388400000L, entry.getScheduledAt().toDate().getTime()); // 월요일 서울 08:00
        assertEquals("Asia/Seoul", entry.getTimeZone());
        assertEquals(1, entry.getPlanVersion());
        assertEquals(1, entry.getRevision());
        assertEquals(uid, entry.getCreatedByUid());
        assertEquals(uid, entry.getUpdatedByUid());
        assertNull(entry.getCompletedAt());
        assertNotNull(entry.getCreatedAt());
        assertEquals(entry.getCreatedAt(), entry.getUpdatedAt());
        assertEquals(FirebaseFirestoreException.Code.ALREADY_EXISTS, firestoreError(secondDevice.saveRecord(id, date, false)));
        String sunday = await(records.saveRecord(id, "2026-11-29", false));
        assertNotEquals(recordId, sunday);
        assertEquals("skipped", await(collection.document(sunday).get(Source.SERVER)).getString("status"));
        String otherMeal = await(plans.createPlan(breakfastPlan()));
        assertNotEquals(recordId, await(records.saveRecord(otherMeal, date, true)));
        assertEquals(3, await(collection.get(Source.SERVER)).size());
        await(plans.deletePlan(id));
        assertEquals(recordId, await(records.saveRecord(id, date, true)));
        assertEquals(saved.getData(), await(ref.get(Source.SERVER)).getData());
    }

    @Test public void mealRecordsRequirePatientAndUseScheduleAtTimeOfMeal() throws Exception {
        String uid = patient.getId();
        MealPlan meal = breakfastPlan();
        meal.setWeeklySchedule(Collections.singletonList(meal.getWeeklySchedule().get(0))); // 평일만
        meal.configureReminder(false, 0);
        String id = await(new MealPlanRepository(db, uid).createPlan(meal));
        DocumentReference root = patient.collection("mealPlans").document(id);
        Timestamp cutoff = new Timestamp(1795478400L, 0); // 화요일 서울 09:00부터 중단
        Map<String, Object> original = await(root.get(Source.SERVER)).getData();
        original.put("supersededAt", cutoff);
        await(root.collection("versions").document("1").set(original));
        await(root.update("status", "stopped", "mealName", "변경된 이름", "updatedAt", cutoff, "version", 2L));
        String date = "2026-11-23";
        MealRecordRepository records = new MealRecordRepository(db, uid, () -> uid);
        MealRecordRepository other = new MealRecordRepository(db, uid, () -> uid + "-care");
        MealRecordRepository signedOut = new MealRecordRepository(db, uid, () -> null);
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(other.saveRecord(id, date, true)));
        assertEquals(FirebaseFirestoreException.Code.UNAUTHENTICATED, firestoreError(signedOut.saveRecord(id, date, true)));
        assertTrue(records.saveRecord(id, "2026-02-30", true).getException() instanceof IllegalArgumentException);
        assertTrue(records.saveRecord("bad/id", date, true).getException() instanceof IllegalArgumentException);
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(records.saveRecord("missing", date, true)));
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(records.saveRecord(id, "2026-11-22", true)));
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(records.saveRecord(id, "2026-11-25", true)));
        CollectionReference collection = patient.collection("mealRecords");
        assertTrue(await(collection.get(Source.SERVER)).isEmpty());
        String recorded = await(records.saveRecord(id, date, false));
        MealRecord entry = await(collection.document(recorded).get(Source.SERVER)).toObject(MealRecord.class);
        assertEquals(1, entry.getPlanVersion());
        assertEquals("아침 식사", entry.getMealSnapshot().get("mealName"));
        assertEquals("skipped", entry.getStatus());
        assertNull(entry.getCompletedAt());
    }

    @Test public void dailyRecordReadsReturnStoredResultsOnlyForThatPatientAndDate() throws Exception {
        String uid = patient.getId();
        String day = "2026-11-23";
        MedicationRecordRepository meds = new MedicationRecordRepository(db, uid, () -> uid);
        MealRecordRepository meals = new MealRecordRepository(db, uid, () -> uid);
        String planId = await(repository.createPlan(plan, drug));
        String mealId = await(new MealPlanRepository(db, uid).createPlan(breakfastPlan()));
        assertTrue(await(meds.getRecords(day)).isEmpty());
        assertTrue(await(meals.getRecords(day)).isEmpty());
        String medRecordId = await(meds.saveRecord(planId, "morning", day, false));
        String mealRecordId = await(meals.saveRecord(mealId, day, false));
        await(meds.saveRecord(planId, "morning", "2026-11-29", true));
        await(meals.saveRecord(mealId, "2026-11-29", true));
        List<MedicationRecord> medDay = await(meds.getRecords(day));
        List<MealRecord> mealDay = await(meals.getRecords(day));
        assertEquals(1, medDay.size());
        assertEquals(medRecordId, medDay.get(0).getRecordId());
        assertEquals("notTaken", medDay.get(0).getStatus());
        assertEquals(1, mealDay.size());
        assertEquals(mealRecordId, mealDay.get(0).getRecordId());
        assertEquals("skipped", mealDay.get(0).getStatus());
        await(patient.collection("mealRecords").document(mealRecordId).update("status", "voided"));
        await(patient.collection("medicationRecords").document(medRecordId).update("status", "voided"));
        assertEquals("voided", await(meals.getRecords(day)).get(0).getStatus());
        assertEquals("voided", await(meds.getRecords(day)).get(0).getStatus());

        String other = uid + "-other";
        assertTrue(await(new MedicationRecordRepository(db, other, () -> other).getRecords(day)).isEmpty());
        assertTrue(await(new MealRecordRepository(db, other, () -> other).getRecords(day)).isEmpty());
        assertTrue(meds.getRecords("2026-02-30").getException() instanceof IllegalArgumentException);
        assertTrue(meals.getRecords(null).getException() instanceof IllegalArgumentException);
    }

    @Test public void caregiverReadsRequireActiveGrantForEachRecordType() throws Exception {
        String uid = patient.getId();
        String day = "2026-11-23";
        String caregiver = uid + "-care";
        MedicationRecordRepository meds = new MedicationRecordRepository(db, uid, () -> caregiver);
        MealRecordRepository meals = new MealRecordRepository(db, uid, () -> caregiver);
        DocumentReference link = db.collection("careLinks").document();
        await(link.set(Collections.singletonMap("patientUid", uid)));
        await(link.update("caregiverUid", caregiver, "status", "active", "permissions.readMeals", true,
                "permissions.readMedications", false, "permissions.recordMedication", false));
        assertTrue(await(meals.getRecords(day)).isEmpty());
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(meds.getRecords(day)));
        assertCounts(await(meals.getStatistics(day, day)), 0, 0, 0, 0);
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(meds.getStatistics(day, day)));
        await(link.update("permissions.readMeals", false, "permissions.readMedications", true));
        assertTrue(await(meds.getRecords(day)).isEmpty());
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(meals.getRecords(day)));
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(meals.getRecords(day, "2026-11-29")));
        await(link.update("status", "revoked"));
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(meds.getRecords(day)));
        assertEquals(FirebaseFirestoreException.Code.UNAUTHENTICATED,
                firestoreError(new MealRecordRepository(db, uid, () -> null).getRecords(day)));
    }

    @Test public void mealCorrectionsAndCancellationKeepPreviousContentsAndAuditTimes() throws Exception {
        String uid = patient.getId();
        String day = "2026-11-23";
        MealRecordRepository meals = new MealRecordRepository(db, uid, () -> uid);
        String planId = await(new MealPlanRepository(db, uid).createPlan(breakfastPlan()));
        String id = await(meals.saveRecord(planId, day, false));
        DocumentReference ref = patient.collection("mealRecords").document(id);
        CollectionReference history = ref.collection("revisions");
        DocumentSnapshot initial = await(ref.get(Source.SERVER));
        MealRecord record = await(meals.getRecords(day)).get(0);
        MealRecordRepository other = new MealRecordRepository(db, uid, () -> uid + "-care");
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(other.cancelRecord(record)));
        assertEquals(Integer.valueOf(1), await(meals.correctRecord(record, false)));
        assertTrue(await(history.get(Source.SERVER)).isEmpty());

        Timestamp actual = new Timestamp(1795388400L, 123456789);
        record.setMealSnapshot(Collections.singletonMap("mealName", "임의 수정"));
        assertEquals(Integer.valueOf(2), await(meals.correctRecord(record, true, actual, "식사 확인")));
        MealRecord corrected = await(meals.getRecords(day)).get(0);
        assertEquals("completed", corrected.getStatus());
        assertEquals(new Timestamp(actual.getSeconds(), 123456000), corrected.getCompletedAt());
        assertEquals("식사 확인", corrected.getNote());
        assertEquals(initial.get("mealSnapshot"), corrected.getMealSnapshot());
        assertEquals(initial.getTimestamp("createdAt"), corrected.getCreatedAt());
        assertEquals(Integer.valueOf(2), await(meals.correctRecord(corrected, true, actual, "식사 확인")));
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(meals.cancelRecord(record)));
        DocumentSnapshot first = await(history.document("1").get(Source.SERVER));
        for (Map.Entry<String, Object> field : initial.getData().entrySet()) {
            assertEquals(field.getValue(), first.get(field.getKey()));
        }
        assertEquals(uid, first.getString("supersededByUid"));
        assertEquals(corrected.getUpdatedAt(), first.getTimestamp("supersededAt"));
        assertEquals(Integer.valueOf(3), await(meals.cancelRecord(corrected)));
        MealRecord cancelled = await(meals.getRecords(day)).get(0);
        assertEquals("voided", cancelled.getStatus());
        assertNull(cancelled.getCompletedAt());
        assertEquals("식사 확인", cancelled.getNote());
        assertEquals(corrected.getCompletedAt(), await(history.document("2").get(Source.SERVER)).getTimestamp("completedAt"));
        assertEquals(Integer.valueOf(3), await(meals.cancelRecord(cancelled)));
        assertEquals(2, await(history.get(Source.SERVER)).size());
        assertEquals(Integer.valueOf(4), await(meals.correctRecord(cancelled, false, actual, null)));
        MealRecord restored = await(meals.getRecords(day)).get(0);
        assertEquals("skipped", restored.getStatus());
        assertNull(restored.getCompletedAt());
        assertNull(restored.getNote());
    }

    @Test public void competingRecordCorrectionAndCancellationCommitOnlyOneRevision() throws Exception {
        openLocalMirror();
        String uid = patient.getId();
        String day = "2026-11-23";
        MedicationRecordRepository a = new MedicationRecordRepository(db, uid, () -> uid);
        MedicationRecordRepository b = new MedicationRecordRepository(reader, uid, () -> uid);
        String planId = await(repository.createPlan(plan, drug));
        String id = await(a.saveRecord(planId, "morning", day, true));
        MedicationRecord first = await(a.getRecords(day)).get(0);
        MedicationRecord second = await(b.getRecords(day)).get(0);
        Task<Integer> edit = a.correctRecord(first, false);
        Task<Integer> cancel = b.cancelRecord(second);
        await(Tasks.whenAllComplete(edit, cancel));
        assertNotEquals(edit.isSuccessful(), cancel.isSuccessful());
        assertEquals(FirebaseFirestoreException.Code.FAILED_PRECONDITION, firestoreError(edit.isSuccessful() ? cancel : edit));
        MedicationRecord result = await(a.getRecords(day)).get(0);
        assertEquals(2, result.getRevision());
        assertEquals(edit.isSuccessful() ? "notTaken" : "voided", result.getStatus());
        DocumentReference ref = patient.collection("medicationRecords").document(id);
        assertEquals(1, await(ref.collection("revisions").get(Source.SERVER)).size());
        assertEquals("taken", await(ref.collection("revisions").document("1").get(Source.SERVER)).getString("status"));
        assertEquals(first.getDoseSnapshot(), result.getDoseSnapshot());
    }

    @Test public void medicationChangesUseStoredAuthorAndCurrentCaregiverGrant() throws Exception {
        String uid = patient.getId();
        String actor = uid + "-care";
        String day = "2026-11-23";
        MedicationRecordRepository owner = new MedicationRecordRepository(db, uid, () -> uid);
        MedicationRecordRepository caregiver = new MedicationRecordRepository(db, uid, () -> actor);
        String planId = await(repository.createPlan(plan, drug));
        await(owner.saveRecord(planId, "morning", day, true));
        DocumentReference link = db.collection("careLinks").document();
        await(link.set(Collections.singletonMap("patientUid", uid)));
        await(link.update("caregiverUid", actor, "status", "active", "permissions.readMedications", true,
                "permissions.recordMedication", true));
        MedicationRecord patientRecord = await(caregiver.getRecords(day)).get(0);
        patientRecord.setCreatedByUid(actor);
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(caregiver.cancelRecord(patientRecord)));

        String sunday = "2026-11-29";
        String id = await(caregiver.saveRecord(planId, "morning", sunday, false));
        DocumentReference ref = patient.collection("medicationRecords").document(id);
        MedicationRecord own = await(caregiver.getRecords(sunday)).get(0);
        Timestamp takenAt = new Timestamp(1795908600L, 0);
        assertEquals(Integer.valueOf(2), await(caregiver.correctRecord(own, true, takenAt, "복용 확인")));
        MedicationRecord corrected = await(owner.getRecords(sunday)).get(0);
        assertEquals(takenAt, corrected.getTakenAt());
        assertEquals(actor, corrected.getUpdatedByUid());
        await(link.update("permissions.recordMedication", false));
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreError(caregiver.cancelRecord(corrected)));
        assertEquals(Integer.valueOf(3), await(owner.cancelRecord(corrected)));
        MedicationRecord cancelled = await(owner.getRecords(sunday)).get(0);
        assertNull(cancelled.getTakenAt());
        assertEquals(actor, cancelled.getCreatedByUid());
        assertEquals(uid, cancelled.getUpdatedByUid());
        assertEquals(uid, await(ref.collection("revisions").document("2").get(Source.SERVER)).getString("supersededByUid"));
    }

    @Test public void weeklyReadsAndCountsIncludeDisabledRemindersVoidsAndBothBoundaries() throws Exception {
        String uid = patient.getId();
        MedicationRecordRepository meds = new MedicationRecordRepository(db, uid, () -> uid);
        MealRecordRepository meals = new MealRecordRepository(db, uid, () -> uid);
        MealPlan breakfast = breakfastPlan();
        breakfast.configureReminder(false, 0);
        String meal = await(new MealPlanRepository(db, uid).createPlan(breakfast));
        slot.setDaysOfWeek(Arrays.asList(1, 2, 3, 4, 5, 6, 7));
        plan.configureReminder(false, 0, 0);
        String medication = await(repository.createPlan(plan, drug));
        String start = "2026-11-23", end = "2026-11-29";
        for (String day : Arrays.asList(start, "2026-11-24", "2026-11-25", end)) {
            await(meds.saveRecord(medication, "morning", day, true));
            await(meals.saveRecord(meal, day, true));
        }
        for (String day : Arrays.asList("2026-11-26", "2026-11-27", "2026-11-30")) {
            await(meds.saveRecord(medication, "morning", day, false));
            await(meals.saveRecord(meal, day, false));
        }
        await(meds.cancelRecord(await(meds.getRecords("2026-11-27")).get(0)));
        await(meals.cancelRecord(await(meals.getRecords("2026-11-27")).get(0)));
        List<MedicationRecord> medWeek = await(meds.getRecords(start, end));
        List<MealRecord> mealWeek = await(meals.getRecords(start, end));
        assertEquals(6, medWeek.size());
        assertEquals(start, medWeek.get(0).getScheduleDate());
        assertEquals(end, medWeek.get(5).getScheduleDate());
        assertEquals(6, mealWeek.size());
        assertEquals("voided", mealWeek.get(4).getStatus());
        assertCounts(await(meds.getStatistics(start, end)), 7, 4, 1, 2);
        assertCounts(await(meals.getStatistics(start, end)), 7, 4, 1, 2);
        assertCounts(await(meds.getStatistics(end, end)), 1, 1, 0, 0);
        assertTrue(meds.getStatistics(end, start).getException() instanceof IllegalArgumentException);
        assertTrue(meals.getRecords("2026-02-30", end).getException() instanceof IllegalArgumentException);
        String otherUid = uid + "-other";
        assertCounts(await(new MealRecordRepository(db, otherUid, () -> otherUid).getStatistics(start, end)), 0, 0, 0, 0);
    }

    @Test public void statisticsDeduplicateVersionsAndUseTheScheduleEffectiveAtEachOccurrence() throws Exception {
        String uid = patient.getId();
        MealPlanRepository plans = new MealPlanRepository(db, uid);
        String id = await(plans.createPlan(breakfastPlan()));
        DocumentReference ref = patient.collection("mealPlans").document(id);
        // 월요일 08:00 식사 후 09:00에 월요일 12:00 일정으로 변경. 같은 계획·날짜는 한 건입니다.
        Timestamp cutoff = new Timestamp(1795392000L, 0);
        Map<String, Object> old = await(ref.get(Source.SERVER)).getData();
        old.put("supersededAt", cutoff);
        await(ref.collection("versions").document("1").set(old));
        MealScheduleEntry monday = new MealScheduleEntry();
        monday.setDaysOfWeek(Collections.singletonList(1));
        monday.setLocalTime("12:00");
        await(ref.update("updatedAt", cutoff, "version", 2L, "weeklySchedule", Collections.singletonList(monday)));
        MealRecordRepository meals = new MealRecordRepository(db, uid, () -> uid);
        assertCounts(await(meals.getStatistics("2026-11-23", "2026-11-29")), 1, 0, 0, 1);
        // 화요일은 식사 시각이 미정이지만 연동 복약 계획에는 화요일 회차가 남습니다.
        slot.setTimingType("mealRelative");
        slot.setMealPlanId(id);
        slot.setOffsetMinutes(30);
        slot.setDaysOfWeek(Arrays.asList(1, 2));
        await(repository.createPlan(plan, drug));
        MedicationRecordRepository meds = new MedicationRecordRepository(db, uid, () -> uid);
        assertCounts(await(meds.getStatistics("2026-11-23", "2026-11-29")), 2, 0, 0, 2);
    }

    private static void assertCounts(RecordStatistics stats, long scheduled, long completed, long missed, long unconfirmed) {
        assertEquals(scheduled, stats.getScheduledCount());
        assertEquals(completed, stats.getCompletedCount());
        assertEquals(missed, stats.getMissedCount());
        assertEquals(unconfirmed, stats.getUnconfirmedCount());
    }

    @Test public void currentUserCombinesOwnAccountAndPublicProfile() throws Exception {
        String uid = patient.getId();
        UserRepository users = new UserRepository(db, () -> uid);
        DocumentReference account = db.collection("users").document(uid);
        DocumentReference profile = db.collection("publicProfiles").document(uid);
        Timestamp created = new Timestamp(100, 0);
        Timestamp updated = new Timestamp(200, 0);
        await(account.set(Collections.singletonMap("role", "caregiver")));
        await(account.update("status", "active", "createdAt", created, "updatedAt", updated));
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(users.getCurrentUser()));

        await(profile.set(Collections.singletonMap("nickname", "테스트 보호자")));
        User user = await(users.getCurrentUser());
        assertEquals(uid, user.getUid());
        assertEquals("caregiver", user.getRole());
        assertEquals("active", user.getStatus());
        assertEquals(created, user.getCreatedAt());
        assertEquals(updated, user.getUpdatedAt());
        assertEquals("테스트 보호자", user.getNickname());
        assertNull(user.getProfileImagePath());
        await(profile.update("profileImagePath", "images/test.jpg"));
        assertEquals("images/test.jpg", await(users.getCurrentUser()).getProfileImagePath());
        AtomicReference<String> session = new AtomicReference<>(uid);
        UserRepository switched = new UserRepository(db, () -> session.getAndSet("other"));
        assertEquals(FirebaseFirestoreException.Code.UNAUTHENTICATED, firestoreError(switched.getCurrentUser()));
        await(profile.update("nickname", FieldValue.delete()));
        assertEquals(FirebaseFirestoreException.Code.DATA_LOSS, firestoreError(users.getCurrentUser()));
        assertEquals(FirebaseFirestoreException.Code.UNAUTHENTICATED,
                firestoreError(new UserRepository(db, () -> null).getCurrentUser()));
    }

    @Test public void myCareLinksReturnsActiveLinksInEachMembersRole() throws Exception {
        String uid = patient.getId();
        String caregiver = uid + "-giver";
        CollectionReference users = db.collection("users");
        CollectionReference links = db.collection("careLinks");
        WriteBatch batch = db.batch();
        batch.set(users.document(uid), Collections.singletonMap("role", "patient"));
        batch.set(users.document(caregiver), Collections.singletonMap("role", "caregiver"));
        Map<String, Boolean> grants = new HashMap<>();
        grants.put("readMeals", true);
        grants.put("readMedications", true);
        grants.put("recordMedication", true);
        batch.set(links.document(uid + "-a"), careLinkFields(uid, caregiver, "active", grants));
        batch.set(links.document(uid + "-b"), careLinkFields(uid, caregiver + "2", "active", null));
        batch.set(links.document(uid + "-c"), careLinkFields(uid + "2", caregiver, "active",
                Collections.singletonMap("recordMedication", true)));
        batch.set(links.document(uid + "-d"), careLinkFields(uid, caregiver + "3", "revoked", grants));
        batch.set(links.document(uid + "-e"), careLinkFields(uid + "3", caregiver, "revoked", grants));
        batch.set(links.document(uid + "-f"), careLinkFields(caregiver, uid, "active", grants));
        await(batch.commit());

        List<CareLink> patientLinks = await(new CareLinkRepository(db, () -> uid).getMyCareLinks());
        assertEquals(2, patientLinks.size());
        Map<String, CareLink> byCaregiver = new HashMap<>();
        for (CareLink link : patientLinks) byCaregiver.put(link.getCaregiverUid(), link);
        CareLink shared = byCaregiver.get(caregiver);
        assertEquals(uid + "-a", shared.getLinkId());
        assertEquals("active", shared.getStatus());
        assertTrue(shared.getPermissions().getReadMeals());
        assertTrue(shared.getPermissions().getReadMedications());
        assertTrue(shared.getPermissions().getRecordMedication());
        assertFalse(byCaregiver.get(caregiver + "2").getPermissions().getReadMeals());

        List<CareLink> caregiverLinks = await(new CareLinkRepository(db, () -> caregiver).getMyCareLinks());
        assertEquals(2, caregiverLinks.size());
        Map<String, CareLink> byPatient = new HashMap<>();
        for (CareLink link : caregiverLinks) byPatient.put(link.getPatientUid(), link);
        assertEquals(shared.getLinkId(), byPatient.get(uid).getLinkId());
        assertFalse(byPatient.get(uid + "2").getPermissions().getRecordMedication());
    }

    private static Map<String, Object> careLinkFields(String patientUid, String caregiverUid, String status,
                                                       Map<String, Boolean> permissions) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("patientUid", patientUid);
        fields.put("caregiverUid", caregiverUid);
        fields.put("status", status);
        if (permissions != null) fields.put("permissions", permissions);
        return fields;
    }

    @Test public void myCareLinksDistinguishesEmptyResultsFromAccountErrors() throws Exception {
        String uid = patient.getId();
        CareLinkRepository links = new CareLinkRepository(db, () -> uid);
        DocumentReference account = db.collection("users").document(uid);
        assertEquals(FirebaseFirestoreException.Code.UNAUTHENTICATED,
                firestoreError(new CareLinkRepository(db, () -> null).getMyCareLinks()));
        assertEquals(FirebaseFirestoreException.Code.NOT_FOUND, firestoreError(links.getMyCareLinks()));
        await(account.set(Collections.singletonMap("role", "unknown")));
        assertEquals(FirebaseFirestoreException.Code.DATA_LOSS, firestoreError(links.getMyCareLinks()));
        await(account.update("role", "patient"));
        assertTrue(await(links.getMyCareLinks()).isEmpty());

        // 계정 조회와 연결 쿼리가 모두 실행된 뒤 로그아웃해도 결과가 전달되지 않습니다.
        AtomicInteger checks = new AtomicInteger();
        CareLinkRepository signedOut = new CareLinkRepository(db, () -> checks.incrementAndGet() <= 2 ? uid : null);
        assertEquals(FirebaseFirestoreException.Code.UNAUTHENTICATED, firestoreError(signedOut.getMyCareLinks()));
    }

    private static FirebaseFirestoreException.Code firestoreError(Task<?> task) throws Exception {
        try {
            await(task);
            throw new AssertionError("Firestore 요청 실패를 예상했습니다.");
        } catch (ExecutionException error) {
            return ((FirebaseFirestoreException) error.getCause()).getCode();
        }
    }
}
