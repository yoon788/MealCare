package com.example.mealcare;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 2.2 보호자: 초대 코드 입력 (screen_05 디자인)
 * 1단계 "코드 확인" → 환자 카드 표시 → 2단계 "OOO 환자와 연결하기" → links 문서 생성
 */
public class LinkPatientActivity extends AppCompatActivity {

    private FirebaseFirestore db;
    private String guardianUid;
    private EditText etCode;
    private TextView tvCodeHelp, tvPatientTitle, tvPatientName;
    private View cardPatient;
    private Button btnConnect;

    // 코드 확인이 끝난 상태에서만 값이 들어 있음
    private String checkedCode, checkedPatientUid, checkedPatientName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_link_patient);

        db = FirebaseFirestore.getInstance();
        guardianUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        etCode = findViewById(R.id.etCode);
        tvCodeHelp = findViewById(R.id.tvCodeHelp);
        tvPatientTitle = findViewById(R.id.tvPatientTitle);
        tvPatientName = findViewById(R.id.tvPatientName);
        cardPatient = findViewById(R.id.cardPatient);
        btnConnect = findViewById(R.id.btnConnect);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 코드를 고치면 다시 확인 단계로 돌아감
        etCode.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { resetCheck(); }
        });

        btnConnect.setOnClickListener(v -> {
            if (checkedCode == null) checkCode();
            else connect();
        });
    }

    private void resetCheck() {
        checkedCode = null;
        checkedPatientUid = null;
        checkedPatientName = null;
        tvPatientTitle.setVisibility(View.GONE);
        cardPatient.setVisibility(View.GONE);
        tvCodeHelp.setText("코드는 영문과 숫자로 구성되어 있습니다.");
        btnConnect.setText("코드 확인");
    }

    /** 1단계: 코드가 유효한지 확인하고 연결할 환자를 보여준다 */
    private void checkCode() {
        String code = etCode.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (code.length() != 6) {
            tvCodeHelp.setText("6자리 코드를 입력해 주세요.");
            return;
        }

        btnConnect.setEnabled(false);
        db.collection("invites").document(code).get()
                .addOnSuccessListener(snap -> {
                    btnConnect.setEnabled(true);
                    String error = validateInvite(snap);
                    if (error != null) {
                        tvCodeHelp.setText(error);
                        return;
                    }
                    checkedCode = code;
                    checkedPatientUid = snap.getString("patientUid");
                    checkedPatientName = snap.getString("patientName");

                    tvCodeHelp.setText("확인되었습니다. 아래 환자와 연결할 수 있습니다.");
                    tvPatientName.setText(checkedPatientName + " 환자");
                    tvPatientTitle.setVisibility(View.VISIBLE);
                    cardPatient.setVisibility(View.VISIBLE);
                    btnConnect.setText(checkedPatientName + " 환자와 연결하기");
                })
                .addOnFailureListener(e -> {
                    btnConnect.setEnabled(true);
                    toast("네트워크 오류: " + e.getMessage());
                });
    }

    /** 문제가 있으면 안내 문구를, 정상이면 null을 돌려준다 */
    private String validateInvite(DocumentSnapshot snap) {
        if (!snap.exists()) return "올바른 초대 코드가 아닙니다.";
        if (Boolean.TRUE.equals(snap.getBoolean("used"))) return "이미 사용된 코드입니다.";
        Timestamp exp = snap.getTimestamp("expiresAt");
        if (exp == null || exp.toDate().before(new Date())) {
            return "만료된 코드입니다. 환자에게 새 코드를 요청하세요.";
        }
        return null;
    }

    /** 2단계: links 생성 + 초대 코드 사용 처리를 트랜잭션 하나로 묶어서 한쪽만 저장되는 일을 막는다 */
    private void connect() {
        DocumentReference inviteRef = db.collection("invites").document(checkedCode);
        // linkId = 환자uid_보호자uid → 같은 두 사람이 중복으로 연결되지 않음
        DocumentReference linkRef = db.collection("links").document(checkedPatientUid + "_" + guardianUid);
        String patientUid = checkedPatientUid;
        String patientName = checkedPatientName;

        btnConnect.setEnabled(false);
        db.runTransaction(tx -> {
            // 트랜잭션 안에서는 읽기를 먼저 다 하고 쓰기를 해야 한다
            DocumentSnapshot invite = tx.get(inviteRef);
            DocumentSnapshot link = tx.get(linkRef);

            String error = validateInvite(invite); // 그 사이 다른 사람이 썼을 수도 있으니 다시 확인
            if (error == null && link.exists()) error = "이미 연결된 환자입니다.";
            if (error != null) {
                throw new FirebaseFirestoreException(error, FirebaseFirestoreException.Code.ABORTED);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("patientUid", patientUid);
            data.put("guardianUid", guardianUid);
            data.put("createdAt", FieldValue.serverTimestamp());

            tx.set(linkRef, data);
            tx.update(inviteRef, "used", true);
            return null;
        }).addOnSuccessListener(unused -> {
            toast(patientName + " 환자와 연결되었습니다.");
            finish(); // 보호자 홈으로 돌아가면 목록이 새로고침됨
        }).addOnFailureListener(e -> {
            btnConnect.setEnabled(true);
            tvCodeHelp.setText(e.getMessage());
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
