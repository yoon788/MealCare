const {createHash, randomBytes} = require("node:crypto");
const {initializeApp} = require("firebase-admin/app");
const {getFirestore, FieldValue, Timestamp} = require("firebase-admin/firestore");
const {onCall, HttpsError} = require("firebase-functions/v2/https");

initializeApp();
const db = getFirestore();
const inviteLifetimeMs = 24 * 60 * 60 * 1000;
const attemptWindowMs = 10 * 60 * 1000;
const maxAttempts = 5;

function actor(request) {
  const uid = request.auth?.uid;
  if (!uid || (request.data?.expectedUid !== undefined && request.data.expectedUid !== uid)) {
    throw new HttpsError("unauthenticated", "로그인 계정을 확인해 주세요.");
  }
  return uid;
}

function requireRole(account, role) {
  if (account.get("role") !== role || account.get("status") !== "active") {
    throw new HttpsError("permission-denied", "이 작업을 수행할 수 있는 활성 회원이 아닙니다.");
  }
}

function permissions(input) {
  const keys = ["readMeals", "readMedications", "recordMedication"];
  if (!input || keys.some(key => typeof input[key] !== "boolean") ||
      (input.recordMedication && !input.readMedications)) {
    throw new HttpsError("invalid-argument", "공유 권한을 확인해 주세요. 대리 기록에는 복약 조회 권한이 필요합니다.");
  }
  return Object.fromEntries(keys.map(key => [key, input[key]]));
}

function hash(text) {
  return createHash("sha256").update(text).digest("hex");
}

exports.createInvite = onCall(async request => {
  const uid = actor(request);
  const grants = permissions(request.data?.permissions);
  const raw = randomBytes(12).toString("hex").toUpperCase();
  const invite = db.collection("invites").doc(hash(raw));
  return db.runTransaction(async tx => {
    requireRole(await tx.get(db.collection("users").doc(uid)), "patient");
    const now = Timestamp.now();
    const expiresAt = Timestamp.fromMillis(now.toMillis() + inviteLifetimeMs);
    tx.create(invite, {inviteId: invite.id, patientUid: uid, permissions: grants,
      status: "active", createdAt: now, expiresAt});
    // 원문은 발급 응답으로만 전달합니다. 공백 없이 네 글자씩 나눠 복사할 수 있습니다.
    return {inviteId: invite.id, code: raw.match(/.{4}/g).join("-"), expiresAtMillis: expiresAt.toMillis()};
  });
});

// 실패한 코드도 횟수에 포함되도록 수락 트랜잭션과 별도로 커밋합니다.
async function countAttempt(uid) {
  const counter = db.collection("inviteAttempts").doc(uid);
  await db.runTransaction(async tx => {
    const [account, previous] = await tx.getAll(db.collection("users").doc(uid), counter);
    requireRole(account, "caregiver");
    const now = Date.now();
    const resetAt = previous.get("resetAt");
    const inWindow = resetAt && now < resetAt.toMillis();
    const count = inWindow ? previous.get("count") : 0;
    if (count >= maxAttempts) throw new HttpsError("resource-exhausted", "잠시 후 다시 시도해 주세요.");
    tx.set(counter, {count: count + 1, resetAt: inWindow ? resetAt : Timestamp.fromMillis(now + attemptWindowMs)});
  });
}

exports.acceptInvite = onCall(async request => {
  const uid = actor(request);
  await countAttempt(uid);
  const code = request.data?.code;
  const compact = typeof code === "string" && code.length <= 64 ? code.replace(/[-\s]/g, "").toUpperCase() : "";
  if (!/^[A-F0-9]{24}$/.test(compact)) throw new HttpsError("invalid-argument", "초대 코드 형식을 확인해 주세요.");
  const invite = db.collection("invites").doc(hash(compact));
  return db.runTransaction(async tx => {
    const [invitation, caregiver] = await tx.getAll(invite, db.collection("users").doc(uid));
    requireRole(caregiver, "caregiver");
    if (!invitation.exists) throw new HttpsError("not-found", "초대 코드를 찾을 수 없습니다.");
    const data = invitation.data();
    // 응답을 놓친 같은 보호자의 재시도는 기존 연결 ID만 돌려줍니다.
    const retry = data.status === "used" && data.usedByUid === uid;
    if (!retry && (data.status !== "active" || data.expiresAt.toMillis() <= Date.now())) {
      throw new HttpsError("failed-precondition", "사용할 수 없는 초대입니다.");
    }
    if (data.patientUid === uid) throw new HttpsError("permission-denied", "자신과 연결할 수 없습니다.");
    requireRole(await tx.get(db.collection("users").doc(data.patientUid)), "patient");

    // 이전에 자동 ID로 생성한 연결도 같은 환자·보호자 쌍이면 재사용합니다.
    const links = db.collection("careLinks");
    const matching = await tx.get(links.where("patientUid", "==", data.patientUid).where("caregiverUid", "==", uid).limit(2));
    if (matching.size > 1) throw new HttpsError("failed-precondition", "중복된 환자·보호자 연결을 확인해 주세요.");
    const existing = matching.docs[0];
    if (retry) {
      if (existing?.get("status") === "active") return {linkId: existing.id};
      throw new HttpsError("failed-precondition", "이미 사용한 초대입니다.");
    }
    const grants = permissions(data.permissions);
    const now = Timestamp.now();
    if (data.expiresAt.toMillis() <= now.toMillis()) throw new HttpsError("failed-precondition", "만료된 초대입니다.");
    // 두 UID를 배열로 직렬화해 UID 안에 구분 문자가 있어도 다른 쌍과 섞이지 않습니다.
    const link = existing?.ref ?? links.doc(hash(JSON.stringify([data.patientUid, uid])));
    const fields = {linkId: link.id, patientUid: data.patientUid, caregiverUid: uid,
      permissions: grants, status: "active", updatedAt: now};
    if (existing) {
      if (existing.get("status") === "active") throw new HttpsError("already-exists", "이미 연결된 회원입니다.");
      if (existing.get("status") !== "revoked") throw new HttpsError("failed-precondition", "연결 상태를 확인해 주세요.");
      tx.create(link.collection("history").doc(invite.id), existing.data());
      tx.update(link, {...fields, revokedAt: FieldValue.delete(), revokedByUid: FieldValue.delete()});
    } else {
      tx.create(link, {...fields, createdAt: now});
    }
    tx.update(invite, {status: "used", usedAt: now, usedByUid: uid});
    return {linkId: link.id};
  });
});

exports.revokeInvite = onCall(async request => {
  const uid = actor(request);
  const id = request.data?.inviteId;
  if (typeof id !== "string" || !/^[a-f0-9]{64}$/.test(id)) throw new HttpsError("invalid-argument", "초대 ID가 올바르지 않습니다.");
  const invite = db.collection("invites").doc(id);
  await db.runTransaction(async tx => {
    const [invitation, account] = await tx.getAll(invite, db.collection("users").doc(uid));
    requireRole(account, "patient");
    if (!invitation.exists || invitation.get("patientUid") !== uid) throw new HttpsError("not-found", "발급한 초대를 찾을 수 없습니다.");
    if (invitation.get("status") === "revoked") return;
    if (invitation.get("status") !== "active") throw new HttpsError("failed-precondition", "이미 사용한 초대입니다.");
    tx.update(invite, {status: "revoked", revokedAt: Timestamp.now()});
  });
  return {};
});

// 현재 상태를 읽은 트랜잭션 안에서 참여자·역할을 확인하고 변경 전 문서와 결과를 함께 기록합니다.
async function changeLink(request, revoke) {
  const uid = actor(request);
  const id = request.data?.linkId;
  if (typeof id !== "string" || !id.trim() || id.includes("/") || id === "." || id === ".." ||
      /^__.*__$/.test(id) || Buffer.byteLength(id) > 1500) {
    throw new HttpsError("invalid-argument", "연결 ID가 올바르지 않습니다.");
  }
  const link = db.collection("careLinks").doc(id);
  const history = link.collection("history").doc();
  await db.runTransaction(async tx => {
    const [snapshot, account] = await tx.getAll(link, db.collection("users").doc(uid));
    const before = snapshot.data();
    if (!before || (before.patientUid !== uid && before.caregiverUid !== uid)) {
      throw new HttpsError("not-found", "본인의 연결을 찾을 수 없습니다.");
    }
    const role = before.patientUid === uid ? "patient" : "caregiver";
    requireRole(account, role);
    if (!revoke && role !== "patient") throw new HttpsError("permission-denied", "공유 권한은 환자만 변경할 수 있습니다.");
    if (revoke && before.status === "revoked") return;
    if (before.status !== "active") throw new HttpsError("failed-precondition", "활성 연결만 변경할 수 있습니다.");

    const changedAt = FieldValue.serverTimestamp();
    let changes;
    if (revoke) {
      changes = {status: "revoked", revokedAt: changedAt, revokedByUid: uid};
    } else {
      const grants = permissions(request.data.permissions);
      if (Object.keys(grants).every(key => before.permissions?.[key] === grants[key])) return;
      changes = {permissions: grants};
    }
    // 재연결 이력과 같은 위치에 이전 상태를 보존합니다. 같은 요청을 재시도하면 이력이 늘지 않습니다.
    tx.create(history, {...before, action: revoke ? "revoked" : "permissionsUpdated", changedByUid: uid, changedAt, changes});
    tx.update(link, {...changes, updatedAt: changedAt});
  });
  return {};
}

exports.updatePermissions = onCall(request => changeLink(request, false));
exports.revokeLink = onCall(request => changeLink(request, true));
