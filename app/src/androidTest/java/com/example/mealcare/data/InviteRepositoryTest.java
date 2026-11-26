package com.example.mealcare.data;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.mealcare.data.model.CareLink;
import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.Permission;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.functions.FirebaseFunctions;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** 로컬 Auth(9099), Functions(5001), Firestore(8080)를 adb reverse로 연결한 경우에 실행합니다. */
@RunWith(AndroidJUnit4.class)
public class InviteRepositoryTest {
    @Test public void patientIssuesCodeAndBothMembersCanReadTheAcceptedLink() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("inviteEmulators")));
        try (Member patient = new Member(); Member caregiver = new Member()) {
            patient.register("patient");
            caregiver.register("caregiver");
            Permission grants = new Permission();
            grants.setReadMeals(true);
            InviteRepository.IssuedInvite invitation = await(patient.invites.createInvite(grants));
            assertTrue(invitation.expiresAtMillis > 0);
            assertNotEquals(invitation.code, invitation.inviteId);
            String linkId = await(caregiver.invites.acceptInvite(invitation.code));
            for (Member member : new Member[]{patient, caregiver}) {
                List<CareLink> links = await(member.links.getMyCareLinks());
                assertEquals(1, links.size());
                assertEquals(linkId, links.get(0).getLinkId());
                assertEquals(patient.uid(), links.get(0).getPatientUid());
                assertEquals(caregiver.uid(), links.get(0).getCaregiverUid());
                assertTrue(links.get(0).getPermissions().getReadMeals());
                assertFalse(links.get(0).getPermissions().getReadMedications());
            }
            InviteRepository.IssuedInvite unused = await(patient.invites.createInvite(grants));
            await(patient.invites.revokeInvite(unused.inviteId));
        }
    }

    private static <T> T await(Task<T> task) throws Exception {
        return Tasks.await(task, 45, TimeUnit.SECONDS);
    }

    @Test public void changingGrantsAndRevokingLinkTakesEffectOnExistingRepositories() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("inviteEmulators")));
        try (Member patient = new Member(); Member caregiver = new Member()) {
            patient.register("patient");
            caregiver.register("caregiver");
            String uid = patient.uid();
            String day = "2026-11-23";
            DocumentReference owner = patient.db.collection("patients").document(uid);
            await(owner.collection("mealRecords").document("meal").set(Collections.singletonMap("scheduleDate", day)));
            await(owner.collection("medicationPlans").document("plan").set(Collections.singletonMap("status", "active")));
            Permission grants = new Permission();
            grants.setReadMeals(true);
            String code = await(patient.invites.createInvite(grants)).code;
            String linkId = await(caregiver.invites.acceptInvite(code));
            MealRecordRepository meals = new MealRecordRepository(caregiver.db, uid, caregiver::uid);
            MedicationPlanRepository plans = new MedicationPlanRepository(caregiver.db, uid, caregiver::uid);
            MedicationRecordRepository doses = new MedicationRecordRepository(caregiver.db, uid, caregiver::uid);
            assertEquals(1, await(meals.getRecords(day)).size());
            assertDenied(plans.getPlans());

            grants.setReadMedications(true);
            await(patient.links.updatePermissions(linkId, grants));
            assertTrue(await(caregiver.links.getMyCareLinks()).get(0).getPermissions().getReadMedications());
            assertEquals(1, await(plans.getPlans()).size());
            assertEquals("plan", await(plans.getPlan("plan")).getPlanId());
            assertDenied(doses.saveRecord("plan", "morning", day, true));

            DoseSlot morning = new DoseSlot();
            morning.setSlotId("morning");
            morning.setDaysOfWeek(Collections.singletonList(1));
            morning.setTimingType("fixed");
            morning.setLocalTime("08:00");
            morning.setDoseAmount(1.0);
            morning.setDoseUnit("정");
            await(owner.collection("medicationPlans").document("plan").update("drugId", "test",
                    "timeZone", "Asia/Seoul", "startDate", day, "doseSlots", Collections.singletonList(morning)));
            grants.setRecordMedication(true);
            await(patient.links.updatePermissions(linkId, grants));
            String recordId = await(doses.saveRecord("plan", "morning", day, true));

            await(caregiver.links.revokeLink(linkId));
            assertTrue(await(patient.links.getMyCareLinks()).isEmpty());
            assertTrue(await(caregiver.links.getMyCareLinks()).isEmpty());
            assertDenied(meals.getRecords(day));
            assertDenied(doses.getRecords(day));
            assertDenied(doses.saveRecord("plan", "morning", day, true));
            assertDenied(plans.getPlans());
            assertDenied(plans.getPlan("plan"));
            assertEquals(1, await(new MealRecordRepository(patient.db, uid, patient::uid).getRecords(day)).size());
            assertEquals(recordId, await(new MedicationRecordRepository(patient.db, uid, patient::uid)
                    .getRecords(day)).get(0).getRecordId());
        }
    }

    private static void assertDenied(Task<?> task) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(task));
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED,
                ((FirebaseFirestoreException) error.getCause()).getCode());
    }

    private static final class Member implements AutoCloseable {
        final FirebaseApp app;
        final FirebaseAuth auth;
        final FirebaseFirestore db;
        final InviteRepository invites;
        final CareLinkRepository links;

        Member() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            String testId = UUID.randomUUID().toString().replace("-", "");
            FirebaseOptions options = new FirebaseOptions.Builder().setProjectId("demo-mealcare")
                    .setApplicationId("1:1234567890:android:" + testId)
                    // Auth 에뮬레이터 설정은 API 키별로 캐시됩니다. 각 테스트 앱에 유효한 형식의 독립된 가짜 키를 씁니다.
                    .setApiKey("A000000" + testId).build();
            app = FirebaseApp.initializeApp(context, options, testId);
            auth = FirebaseAuth.getInstance(app);
            auth.useEmulator("localhost", 9099);
            db = FirebaseFirestore.getInstance(app);
            db.useEmulator("localhost", 8080);
            FirebaseFunctions functions = FirebaseFunctions.getInstance(app);
            functions.useEmulator("localhost", 5001);
            invites = new InviteRepository(functions, this::uid);
            links = new CareLinkRepository(db, this::uid);
        }

        String uid() {
            return auth.getCurrentUser() == null ? null : auth.getCurrentUser().getUid();
        }

        void register(String role) throws Exception {
            await(auth.signInAnonymously());
            Map<String, Object> fields = new HashMap<>();
            fields.put("role", role);
            fields.put("status", "active");
            await(db.collection("users").document(uid()).set(fields));
        }

        @Override public void close() throws Exception {
            await(db.terminate());
            app.delete();
        }
    }
}
