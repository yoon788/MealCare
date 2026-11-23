// Firebase Emulator Suite에서만 실행합니다. 실제 프로젝트에 테스트 회원을 만들지 않습니다.
const assert = require("node:assert/strict");
const {test, after} = require("node:test");
const {randomUUID, createHash} = require("node:crypto");
if (!/^(127\.0\.0\.1|localhost):\d+$/.test(process.env.FIRESTORE_EMULATOR_HOST ?? "") ||
    !process.env.GCLOUD_PROJECT?.startsWith("demo-")) {
  throw new Error("로컬 Firestore 에뮬레이터와 demo- 프로젝트를 지정해 주세요.");
}
const {createInvite, acceptInvite, revokeInvite, updatePermissions, revokeLink} = require("..");
const {getFirestore, Timestamp} = require("firebase-admin/firestore");
const db = getFirestore();
after(() => db.terminate());
const grants = {readMeals: true, readMedications: true, recordMedication: false};
const call = (fn, uid, data) => fn.run({auth: uid ? {uid} : null, data});
const failed = code => error => error.code === code;

async function member(role) {
  const uid = randomUUID();
  await db.collection("users").doc(uid).set({role, status: "active"});
  return uid;
}

test("동시 수락은 한 보호자만 연결하고 응답 재시도는 같은 ID를 반환합니다", async () => {
  const [patient, first, second] = await Promise.all([member("patient"), member("caregiver"), member("caregiver")]);
  const issued = await call(createInvite, patient, {permissions: grants});
  const invite = db.collection("invites").doc(issued.inviteId);
  const raw = issued.code.replaceAll("-", "");
  assert.equal(issued.inviteId, createHash("sha256").update(raw).digest("hex"));
  const stored = (await invite.get()).data();
  assert.equal(stored.expiresAt.toMillis(), issued.expiresAtMillis);
  assert.equal(stored.expiresAt.toMillis() - stored.createdAt.toMillis(), 24 * 3600_000);
  assert.equal(JSON.stringify(stored).includes(raw), false);
  assert.equal(JSON.stringify(stored).includes(issued.code), false);
  const result = await Promise.allSettled([first, second].map(uid => call(acceptInvite, uid, {code: raw.toLowerCase()})));
  assert.equal(result.filter(item => item.status === "fulfilled").length, 1);
  assert.equal(result.find(item => item.status === "rejected").reason.code, "failed-precondition");
  const winner = (await invite.get()).get("usedByUid");
  const {linkId} = await call(acceptInvite, winner, {code: issued.code});
  const active = db.collection("careLinks").where("status", "==", "active");
  for (const [field, uid] of [["patientUid", patient], ["caregiverUid", winner]]) {
    const links = await active.where(field, "==", uid).get();
    assert.equal(links.size, 1);
    assert.equal(links.docs[0].id, linkId);
    assert.deepEqual(links.docs[0].get("permissions"), grants);
  }
  await assert.rejects(call(revokeInvite, patient, issued), failed("failed-precondition"));
});

test("재연결은 기존 ID와 최초 생성 시각·해제 이력을 보존합니다", async () => {
  const [patient, caregiver] = await Promise.all([member("patient"), member("caregiver")]);
  const previous = db.collection("careLinks").doc();
  const createdAt = Timestamp.fromMillis(1000);
  await previous.set({patientUid: patient, caregiverUid: caregiver, status: "revoked", createdAt,
    revokedAt: Timestamp.now(), revokedByUid: patient, permissions: {...grants, readMeals: false}});
  const issued = await call(createInvite, patient, {permissions: grants});
  assert.deepEqual(await call(acceptInvite, caregiver, issued), {linkId: previous.id});
  const restored = (await previous.get()).data();
  assert.equal(restored.createdAt.toMillis(), 1000);
  assert.equal(restored.revokedAt, undefined);
  assert.deepEqual(restored.permissions, grants);
  assert.equal((await previous.collection("history").doc(issued.inviteId).get()).get("status"), "revoked");
  const another = await call(createInvite, patient, {permissions: {...grants, readMeals: false}});
  await assert.rejects(call(acceptInvite, caregiver, another), failed("already-exists"));
  assert.equal((await db.collection("invites").doc(another.inviteId).get()).get("status"), "active");
  assert.deepEqual((await previous.get()).get("permissions"), grants);
});

test("권한과 발급자를 검증하고 취소·만료된 초대는 사용할 수 없습니다", async () => {
  const [patient, other, caregiver] = await Promise.all([member("patient"), member("patient"), member("caregiver")]);
  await assert.rejects(call(createInvite, null, {permissions: grants}), failed("unauthenticated"));
  await assert.rejects(call(createInvite, patient, {expectedUid: other, permissions: grants}), failed("unauthenticated"));
  await assert.rejects(call(createInvite, caregiver, {permissions: grants}), failed("permission-denied"));
  await assert.rejects(call(createInvite, patient, {permissions: {...grants, readMedications: false, recordMedication: true}}),
    failed("invalid-argument"));
  const issued = await call(createInvite, patient, {permissions: grants});
  await assert.rejects(call(acceptInvite, patient, issued), failed("permission-denied"));
  await assert.rejects(call(revokeInvite, other, issued), failed("not-found"));
  await call(revokeInvite, patient, issued);
  await call(revokeInvite, patient, issued);
  await assert.rejects(call(acceptInvite, caregiver, issued), failed("failed-precondition"));
  const expired = await call(createInvite, patient, {permissions: grants});
  await db.collection("invites").doc(expired.inviteId).update({expiresAt: Timestamp.fromMillis(0)});
  await assert.rejects(call(acceptInvite, caregiver, expired), failed("failed-precondition"));
  assert.equal((await db.collection("careLinks").where("patientUid", "==", patient).get()).size, 0);
});

test("실패한 수락도 누적되며 제한 시간이 지나면 다시 시도할 수 있습니다", async () => {
  const caregiver = await member("caregiver");
  for (let i = 0; i < 5; i++) await assert.rejects(call(acceptInvite, caregiver, {code: "?"}), failed("invalid-argument"));
  await assert.rejects(call(acceptInvite, caregiver, {code: "?"}), failed("resource-exhausted"));
  const counter = db.collection("inviteAttempts").doc(caregiver);
  await counter.update({resetAt: Timestamp.fromMillis(0)});
  await assert.rejects(call(acceptInvite, caregiver, {code: "?"}), failed("invalid-argument"));
  assert.equal((await counter.get()).get("count"), 1);
});

test("환자만 권한을 바꾸고 당사자만 해제합니다. 실제 변경마다 이전 상태와 요청자를 보존합니다", async () => {
  const [p, c, other] = await Promise.all([member("patient"), member("caregiver"), member("patient")]);
  const invitation = await call(createInvite, p, {permissions: grants});
  const {linkId} = await call(acceptInvite, c, invitation);
  const link = db.collection("careLinks").doc(linkId);
  const history = link.collection("history");
  const revised = {...grants, recordMedication: true};
  const change = {linkId, permissions: revised};
  await assert.rejects(call(updatePermissions, null, change), failed("unauthenticated"));
  await assert.rejects(call(updatePermissions, c, change), failed("permission-denied"));
  await assert.rejects(call(updatePermissions, other, change), failed("not-found"));
  await assert.rejects(call(revokeLink, other, {linkId}), failed("not-found"));
  await assert.rejects(call(updatePermissions, p, {linkId, permissions: {...revised, readMedications: false}}),
    failed("invalid-argument"));
  await assert.rejects(call(revokeLink, p, {linkId: "a/b/c"}), failed("invalid-argument"));
  assert.equal((await history.get()).size, 0);

  await Promise.all([call(updatePermissions, p, change), call(updatePermissions, p, change)]);
  const events = await history.get();
  assert.equal(events.size, 1);
  const edit = events.docs[0].data();
  assert.equal(edit.action, "permissionsUpdated");
  assert.equal(edit.changedByUid, p);
  assert.deepEqual(edit.permissions, grants);
  assert.deepEqual(edit.changes.permissions, revised);
  assert.ok(edit.changedAt.isEqual((await link.get()).get("updatedAt")));
  const [first, second] = await Promise.all([call(revokeLink, c, {linkId}), call(revokeLink, p, {linkId})]);
  assert.deepEqual(first, second);
  assert.equal((await history.get()).size, 2);
  const revoked = (await link.get()).data();
  assert.equal(revoked.status, "revoked");
  assert.ok([c, p].includes(revoked.revokedByUid));
  const removal = (await history.where("action", "==", "revoked").get()).docs[0].data();
  assert.equal(removal.status, "active");
  assert.equal(removal.changedByUid, revoked.revokedByUid);
  assert.ok(removal.changedAt.isEqual(revoked.revokedAt));
  assert.deepEqual(removal.permissions, revised);
  await assert.rejects(call(updatePermissions, p, {linkId, permissions: grants}), failed("failed-precondition"));
  assert.equal((await history.get()).size, 2);

  const next = await call(createInvite, p, {permissions: grants});
  assert.deepEqual(await call(acceptInvite, c, next), {linkId});
  assert.equal((await history.get()).size, 3);
});

test("권한 변경과 해제가 경합해도 해제 상태가 유지되고 커밋한 변경마다 이력이 하나 남습니다", async () => {
  const [p, c] = await Promise.all([member("patient"), member("caregiver")]);
  const {linkId} = await call(acceptInvite, c, await call(createInvite, p, {permissions: grants}));
  const permissions = {...grants, readMeals: false};
  const [edit, revoke] = await Promise.allSettled([
    call(updatePermissions, p, {linkId, permissions}), call(revokeLink, p, {linkId})
  ]);
  assert.equal(revoke.status, "fulfilled");
  if (edit.status === "rejected") assert.equal(edit.reason.code, "failed-precondition");
  const link = db.collection("careLinks").doc(linkId);
  assert.equal((await link.get()).get("status"), "revoked");
  assert.equal((await link.collection("history").get()).size, edit.status === "fulfilled" ? 2 : 1);
});
