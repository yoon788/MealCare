/**
 * MealCare DB 사용 안내
 *
 * 환자별 Repository에는 대상 환자의 UID를 넘깁니다.
 * 보호자가 호출할 때도 patientUid에는 연결된 환자의 UID를 넣습니다.
 * Firestore 작업은 Task로 반환됩니다. 저장이 끝난 뒤 할 일은 성공 리스너에 둡니다.
 *
 * <pre>
 * [회원과 보호자 연결]
 *   내 회원 정보       new UserRepository().getCurrentUser()
 *   내 활성 연결       new CareLinkRepository().getMyCareLinks()
 *
 *   InviteRepository     createInvite(permissions) → acceptInvite(code)
 *                        미사용 초대는 revokeInvite(inviteId)로 취소합니다.
 *   CareLinkRepository   updatePermissions(linkId, permissions)
 *                        연결 해제는 revokeLink(linkId)를 호출합니다.
 *
 * [약 검색과 복약 계획 등록]
 *   약 이름 검색       api.searchByName("타이레놀")
 *   선택한 약의 상세   api.getDetails(selectedDrug)
 *   복약 계획 등록     meds.createPlan(plan, selectedDrug)
 *
 *   api는 DrugAPI입니다. 약 검색과 상세 조회는 백그라운드에서 실행합니다.
 *   meds는 new MedicationPlanRepository(patientUid)로 만듭니다.
 *   검색 결과에서 고른 약은 상세 조회 없이도 등록할 수 있습니다.
 *
 * [복약·식사 계획]
 *   복약 계획은 MedicationPlanRepository, 식사 계획은 MealPlanRepository를 씁니다.
 *   식사 계획 등록     new MealPlanRepository(patientUid).createPlan(mealPlan)
 *   목록 / 단건 조회   getPlans() / getPlan(planId)
 *   수정 / 중단        updatePlan(plan) / deletePlan(planId)
 *
 * [복약·식사 기록]
 *   MedicationRecordRepository   saveRecord(planId, slotId, date, taken)
 *   MealRecordRepository         saveRecord(mealPlanId, date, completed)
 *
 *   날짜별 조회        getRecords(date)
 *   기간별 통계        getStatistics(startDate, endDate)
 *   기록 정정 / 취소   correctRecord(record, 결과값) / cancelRecord(record)
 *
 * [Room에서 하루 일정 조회]
 *   ReminderDao dao = ReminderDatabase.getInstance(context).reminders();
 *   복약 일정          dao.getDailyMedicationSchedule(patientUid, date)
 *   식사 일정          dao.getDailyMealSchedule(patientUid, date)
 *
 *   백그라운드에서 조회합니다. date는 "2026-11-23" 형식입니다.
 *   식후 30분 회차는 그날의 식사 예정 시각을 기준으로 계산합니다.
 * </pre>
 *
 * @see UserRepository
 * @see InviteRepository
 * @see CareLinkRepository
 * @see DrugAPI
 * @see MedicationPlanRepository
 * @see MealPlanRepository
 * @see MedicationRecordRepository
 * @see MealRecordRepository
 * @see ReminderDao
 */
package com.example.mealcare.data;

import com.example.mealcare.data.local.ReminderDao;
