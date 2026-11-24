package com.example.mealcare;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mealcare.data.UserRepository;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 보호자 홈 (screen_03 디자인): 이름·날짜 표시, 연결된 환자, 환자 연결(코드 입력), 로그아웃 */
public class GuardianHomeActivity extends AppCompatActivity {

    private final UserRepository users = new UserRepository();
    private FirebaseFirestore db;
    private String uid;
    private TextView tvWelcome, tvPatients, btnLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guardian_home);

        db = FirebaseFirestore.getInstance();
        uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        tvWelcome = findViewById(R.id.tvWelcome);
        tvPatients = findViewById(R.id.tvPatients);
        btnLink = findViewById(R.id.btnLink);

        TextView tvDate = findViewById(R.id.tvDate);
        tvDate.setText(new SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREA).format(new Date()));

        btnLink.setOnClickListener(v ->
                startActivity(new Intent(this, LinkPatientActivity.class)));
        findViewById(R.id.btnLogout).setOnClickListener(v -> AuthRouter.logout(this));

        users.getNickname(uid).addOnSuccessListener(name ->
                tvWelcome.setText(name + "님, 안녕하세요"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPatients(); // 연결 화면에서 돌아올 때마다 새로고침
    }

    private void loadPatients() {
        db.collection("links").whereEqualTo("guardianUid", uid).get()
                .addOnSuccessListener(qs -> {
                    if (qs.isEmpty()) {
                        tvPatients.setText("아직 연결된 환자가 없습니다. 초대 코드를 입력하세요.");
                        btnLink.setText("연결하기");
                        return;
                    }
                    List<Task<String>> tasks = new ArrayList<>();
                    for (DocumentSnapshot link : qs.getDocuments()) {
                        tasks.add(users.getNickname(link.getString("patientUid")));
                    }
                    Tasks.<String>whenAllSuccess(tasks).addOnSuccessListener(names -> {
                        tvPatients.setText(String.join(", ", names) + " · 식사와 복약 기록을 공유 중입니다.");
                        btnLink.setText("연결됨");
                    });
                });
    }
}
