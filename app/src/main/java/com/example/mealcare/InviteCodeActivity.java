package com.example.mealcare;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mealcare.data.CareLinkRepository;
import com.example.mealcare.data.InviteRepository;
import com.example.mealcare.data.UserRepository;
import com.example.mealcare.data.model.Permission;
import com.google.firebase.auth.FirebaseAuth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** 환자에게 서버가 발급한 초대 코드와 만료 시각을 표시합니다. */
public class InviteCodeActivity extends AppCompatActivity {
    private final InviteRepository invites = new InviteRepository();
    private long expiresAtMillis;
    private String uid;
    private String patientName;
    private String currentCode;
    private TextView tvCode, tvCodeBg, tvExpire;
    private Button btnCopy, btnShare;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_invite_code);

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
            if (savedInstanceState != null && uid.equals(savedInstanceState.getString("issuerUid"))
                    && savedInstanceState.getString("inviteCode") != null
                    && savedInstanceState.getLong("expiresAt") > System.currentTimeMillis()) {
                showCode(savedInstanceState.getString("inviteCode"), savedInstanceState.getLong("expiresAt"));
            } else {
                createCode();
            }
        }).addOnFailureListener(e -> tvExpire.setText("환자 정보 조회 실패: " + e.getMessage()));
    }

    /** 뒤에 보이는 '보호자 연결 상태' 카드 채우기 */
    private void loadGuardianStatus() {
        TextView tvStatus = findViewById(R.id.tvGuardianStatus);
        TextView tvChip = findViewById(R.id.tvGuardianChip);
        new CareLinkRepository().getMyCareLinks().addOnSuccessListener(links -> {
            if (links.isEmpty()) {
                tvStatus.setText("아직 연결된 보호자가 없습니다.");
                tvChip.setText("미연결");
            } else {
                tvStatus.setText("보호자 " + links.size() + "명과 기록을 공유하고 있습니다.");
                tvChip.setText("연결됨");
            }
        });
    }

    private void createCode() {
        // 화면에 표시된 조회 범위만 초대에 담습니다. 대리 기록 권한은 부여하지 않습니다.
        CheckBox meals = sharingOption("식사 및 결식 기록");
        CheckBox medications = sharingOption("복약 기록");
        if (meals == null || medications == null) {
            tvExpire.setText("공유 범위를 확인할 수 없어 코드를 발급하지 못했습니다.");
            return;
        }
        Permission grants = new Permission();
        grants.setReadMeals(meals.isChecked());
        grants.setReadMedications(medications.isChecked());
        invites.createInvite(grants)
                .addOnSuccessListener(issued -> showCode(issued.code, issued.expiresAtMillis))
                .addOnFailureListener(e -> tvExpire.setText("초대 코드 발급 실패: " + e.getMessage()));
    }

    // 기존 레이아웃의 공유 체크박스에는 ID가 없어 정확한 표시 문구로 찾습니다.
    private CheckBox sharingOption(String label) {
        ArrayList<View> matches = new ArrayList<>();
        findViewById(android.R.id.content).findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT);
        CheckBox option = null;
        for (View view : matches) {
            if (view instanceof CheckBox && label.contentEquals(((CheckBox) view).getText())) {
                if (option != null) return null;
                option = (CheckBox) view;
            }
        }
        return option;
    }

    private void showCode(String code, long expiresAt) {
        currentCode = code;
        expiresAtMillis = expiresAt;
        tvCode.setText(code);
        tvCodeBg.setText(code);
        tvExpire.setText(expiryTime() + "까지 사용할 수 있습니다. (발급 후 10분)");
        setButtonsEnabled(true);
    }

    private String expiryTime() {
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(expiresAtMillis));
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("issuerUid", uid);
        state.putString("inviteCode", currentCode);
        state.putLong("expiresAt", expiresAtMillis);
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
                "[MealCare] " + patientName + "님의 보호자 초대 코드: " + currentCode + "\n" + expiryTime() + "까지 앱에서 입력해 주세요.");
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
