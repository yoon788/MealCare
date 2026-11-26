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

import com.example.mealcare.data.InviteRepository;

import java.util.Locale;

/** 보호자가 서버에서 환자 이름을 확인한 뒤 같은 코드로 연결을 수락합니다. */
public class LinkPatientActivity extends AppCompatActivity {
    private final InviteRepository invites = new InviteRepository();
    private EditText etCode;
    private TextView tvCodeHelp, tvPatientTitle, tvPatientName;
    private View cardPatient;
    private Button btnConnect;

    // 코드 확인이 끝난 상태에서만 값이 들어 있음
    private String checkedCode, checkedPatientName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_link_patient);

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
        etCode.setEnabled(false);
        invites.previewInvite(code)
                .addOnSuccessListener(name -> {
                    etCode.setEnabled(true);
                    btnConnect.setEnabled(true);
                    checkedCode = code;
                    checkedPatientName = name;
                    tvCodeHelp.setText("확인되었습니다. 아래 환자와 연결할 수 있습니다.");
                    tvPatientName.setText(name + " 환자");
                    tvPatientTitle.setVisibility(View.VISIBLE);
                    cardPatient.setVisibility(View.VISIBLE);
                    btnConnect.setText(name + " 환자와 연결하기");
                }).addOnFailureListener(this::showInviteError);
    }

    /** 서버가 만료와 사용 여부를 다시 확인하고 연결 생성과 코드 사용을 함께 기록합니다. */
    private void connect() {
        etCode.setEnabled(false);
        btnConnect.setEnabled(false);
        invites.acceptInvite(checkedCode)
                .addOnSuccessListener(linkId -> {
                    toast(checkedPatientName + " 환자와 연결되었습니다.");
                    finish();
                }).addOnFailureListener(this::showInviteError);
    }

    private void showInviteError(Exception e) {
        etCode.setEnabled(true);
        btnConnect.setEnabled(true);
        tvCodeHelp.setText(e.getMessage());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
