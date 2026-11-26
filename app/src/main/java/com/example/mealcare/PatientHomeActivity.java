package com.example.mealcare;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mealcare.data.CareLinkRepository;
import com.example.mealcare.data.UserRepository;
import com.example.mealcare.data.model.CareLink;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 환자 홈 (screen_02 디자인): 이름·날짜 표시, 보호자 연결 상태, 연결 관리(초대), 로그아웃 */
public class PatientHomeActivity extends AppCompatActivity {

    private final UserRepository users = new UserRepository();
    private final CareLinkRepository careLinks = new CareLinkRepository();
    private TextView tvWelcome, tvGuardians;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_patient_home);

        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        tvWelcome = findViewById(R.id.tvWelcome);
        tvGuardians = findViewById(R.id.tvGuardians);

        TextView tvDate = findViewById(R.id.tvDate);
        tvDate.setText(new SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREA).format(new Date()));

        findViewById(R.id.btnInvite).setOnClickListener(v ->
                startActivity(new Intent(this, InviteCodeActivity.class)));
        findViewById(R.id.btnLogout).setOnClickListener(v -> AuthRouter.logout(this));

        users.getNickname(uid).addOnSuccessListener(name ->
                tvWelcome.setText(name + " 환자님"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadGuardians(); // 초대 화면에서 돌아올 때마다 새로고침
    }

    private void loadGuardians() {
        careLinks.getMyCareLinks()
                .addOnSuccessListener(links -> {
                    if (links.isEmpty()) {
                        tvGuardians.setText("아직 연결된 보호자가 없습니다.");
                        return;
                    }
                    List<Task<String>> tasks = new ArrayList<>();
                    for (CareLink link : links) {
                        tasks.add(users.getNickname(link.getCaregiverUid()));
                    }
                    Tasks.<String>whenAllSuccess(tasks).addOnSuccessListener(names -> {
                        tvGuardians.setText(String.join(", ", names) + " · 기록을 공유하고 있습니다.");
                    });
                });
    }
}
