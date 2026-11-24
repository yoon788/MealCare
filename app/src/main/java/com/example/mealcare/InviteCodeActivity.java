package com.example.mealcare;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.Timestamp;
import com.example.mealcare.data.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 2.2 환자: 초대 코드 생성 (screen_04 디자인, invites/{code}, 10분 뒤 만료) */
public class InviteCodeActivity extends AppCompatActivity {

    private static final long EXPIRE_MILLIS = 10 * 60 * 1000; // 10분
    private static final int MAX_TRY = 5;
    // 헷갈리는 글자(0, O, 1, I)는 뺀 영문 대문자 + 숫자
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private FirebaseFirestore db;
    private String uid;
    private String patientName;
    private String currentCode;
    private TextView tvCode, tvCodeBg, tvExpire;
    private Button btnCopy, btnShare;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_invite_code);

        db = FirebaseFirestore.getInstance();
        uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        tvCode = findViewById(R.id.tvCode);
        tvCodeBg = findViewById(R.id.tvCodeBg);
        tvExpire = findViewById(R.id.tvExpire);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);

        tvCode.setText("······");
        tvCodeBg.setText("······");
        setButtonsEnabled(false);

        findViewById(R.id.dimView).setOnClickListener(v -> finish()); // 바깥(어두운 부분) 누르면 닫기
        btnCopy.setOnClickListener(v -> copyCode());
        btnShare.setOnClickListener(v -> shareCode());

        loadGuardianStatus();

        // 보호자 확인 화면에 보여줄 환자 이름을 먼저 읽고 코드 생성
        new UserRepository().getNickname(uid).addOnSuccessListener(name -> {
            patientName = name;
            ((TextView) findViewById(R.id.tvHeader)).setText(patientName + "님, 안녕하세요");
            createCode(0);
        });
    }

    /** 뒤에 보이는 '보호자 연결 상태' 카드 채우기 */
    private void loadGuardianStatus() {
        TextView tvStatus = findViewById(R.id.tvGuardianStatus);
        TextView tvChip = findViewById(R.id.tvGuardianChip);
        db.collection("links").whereEqualTo("patientUid", uid).get().addOnSuccessListener(qs -> {
            if (qs.isEmpty()) {
                tvStatus.setText("아직 연결된 보호자가 없습니다.");
                tvChip.setText("미연결");
            } else {
                tvStatus.setText("보호자 " + qs.size() + "명과 기록을 공유하고 있습니다.");
                tvChip.setText("연결됨");
            }
        });
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private void createCode(int attempt) {
        if (attempt >= MAX_TRY) {
            toast("코드를 만들지 못했습니다. 다시 시도해 주세요.");
            return;
        }

        String code = randomCode();
        DocumentReference ref = db.collection("invites").document(code);

        ref.get().addOnSuccessListener(snap -> {
            // 같은 코드가 아직 살아 있으면(미사용 + 미만료) 다른 코드로 다시 시도
            if (snap.exists()) {
                Timestamp exp = snap.getTimestamp("expiresAt");
                boolean used = Boolean.TRUE.equals(snap.getBoolean("used"));
                boolean expired = exp == null || exp.toDate().before(new Date());
                if (!used && !expired) {
                    createCode(attempt + 1);
                    return;
                }
            }

            Date expiresAt = new Date(System.currentTimeMillis() + EXPIRE_MILLIS);
            Map<String, Object> data = new HashMap<>();
            data.put("patientUid", uid);
            data.put("patientName", patientName);
            data.put("createdAt", FieldValue.serverTimestamp());
            data.put("expiresAt", new Timestamp(expiresAt));
            data.put("used", false);

            ref.set(data)
                    .addOnSuccessListener(unused -> {
                        currentCode = code;
                        tvCode.setText(code);
                        tvCodeBg.setText(code);
                        String time = new SimpleDateFormat("HH:mm", Locale.KOREA).format(expiresAt);
                        tvExpire.setText(time + "까지 사용할 수 있습니다. (10분)");
                        setButtonsEnabled(true);
                    })
                    .addOnFailureListener(e -> toast("코드 저장 실패: " + e.getMessage()));
        }).addOnFailureListener(e -> toast("네트워크 오류: " + e.getMessage()));
    }

    private void copyCode() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("MealCare 초대 코드", currentCode));
        toast("초대 코드를 복사했습니다.");
    }

    private void shareCode() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT,
                "[MealCare] " + patientName + "님의 보호자 초대 코드: " + currentCode + "\n10분 안에 앱에서 입력해 주세요.");
        startActivity(Intent.createChooser(intent, "초대 코드 공유"));
    }

    private void setButtonsEnabled(boolean enabled) {
        btnCopy.setEnabled(enabled);
        btnShare.setEnabled(enabled);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
