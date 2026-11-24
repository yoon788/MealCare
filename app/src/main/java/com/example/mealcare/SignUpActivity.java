package com.example.mealcare;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.mealcare.data.UserRepository;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;

/** Authentication 계정 생성 후 회원 정보와 공개 프로필을 함께 등록합니다. */
public class SignUpActivity extends AppCompatActivity {

    private EditText etEmail, etPassword, etPasswordConfirm, etName;
    private LinearLayout cardPatient, cardGuardian;
    private RadioButton rbPatient, rbGuardian;
    private Button btnSignUp;
    private String role = UserRepository.ROLE_PATIENT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etPasswordConfirm = findViewById(R.id.etPasswordConfirm);
        etName = findViewById(R.id.etName);
        cardPatient = findViewById(R.id.cardPatient);
        cardGuardian = findViewById(R.id.cardGuardian);
        rbPatient = findViewById(R.id.rbPatient);
        rbGuardian = findViewById(R.id.rbGuardian);
        btnSignUp = findViewById(R.id.btnSignUp);

        // 계정 유형 카드를 누르면 선택 (screen_01 계정 유형 선택 디자인)
        cardPatient.setOnClickListener(v -> selectRole(UserRepository.ROLE_PATIENT));
        cardGuardian.setOnClickListener(v -> selectRole(UserRepository.ROLE_CAREGIVER));
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSignUp.setOnClickListener(v -> signUp());

        selectRole(UserRepository.ROLE_PATIENT);
    }

    private void selectRole(String newRole) {
        role = newRole;
        boolean isPatient = UserRepository.ROLE_PATIENT.equals(role);
        rbPatient.setChecked(isPatient);
        rbGuardian.setChecked(!isPatient);
        cardPatient.setBackgroundResource(isPatient ? R.drawable.mc_card_outline : R.drawable.mc_card);
        cardGuardian.setBackgroundResource(isPatient ? R.drawable.mc_card : R.drawable.mc_card_outline);
        btnSignUp.setText(isPatient ? "환자 계정으로 시작하기" : "보호자 계정으로 시작하기");
    }

    private void signUp() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etPasswordConfirm.getText().toString();
        String name = etName.getText().toString().trim();

        // 입력 검사
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password) || TextUtils.isEmpty(name)) {
            toast("모든 칸을 입력해 주세요.");
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("이메일 형식이 올바르지 않습니다.");
            return;
        }
        if (password.length() < 6) {
            toast("비밀번호는 6자 이상이어야 합니다.");
            return;
        }
        if (!password.equals(confirm)) {
            toast("비밀번호가 서로 다릅니다.");
            return;
        }

        btnSignUp.setEnabled(false);
        String selectedRole = role;
        FirebaseAuth auth = FirebaseAuth.getInstance();
        auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> saveUser(result.getUser(), name, selectedRole))
                .addOnFailureListener(e -> {
                    btnSignUp.setEnabled(true);
                    if (e instanceof FirebaseAuthUserCollisionException) {
                        // Auth만 생성된 뒤 저장이 실패했으면 같은 계정을 확인하고 문서 생성을 재시도합니다.
                        FirebaseUser current = auth.getCurrentUser();
                        if (current == null || !email.equalsIgnoreCase(current.getEmail())) {
                            toast("이미 가입된 이메일입니다. 로그인해 주세요.");
                            return;
                        }
                        btnSignUp.setEnabled(false);
                        current.reauthenticate(EmailAuthProvider.getCredential(email, password))
                                .addOnSuccessListener(unused -> saveUser(current, name, selectedRole))
                                .addOnFailureListener(error -> {
                                    btnSignUp.setEnabled(true);
                                    toast("계정 확인 실패: " + error.getMessage());
                                });
                    } else if (e instanceof FirebaseAuthWeakPasswordException) {
                        toast("비밀번호가 너무 약합니다.");
                    } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
                        toast("이메일 형식이 올바르지 않습니다.");
                    } else {
                        toast("가입 실패: " + e.getMessage());
                    }
                });
    }

    private void saveUser(FirebaseUser user, String name, String selectedRole) {
        new UserRepository().createProfile(user.getUid(), selectedRole, name)
                .addOnSuccessListener(unused -> {
                    toast("가입이 완료되었습니다.");
                    AuthRouter.goHome(this, user.getUid(), () -> btnSignUp.setEnabled(true));
                })
                .addOnFailureListener(e -> {
                    btnSignUp.setEnabled(true);
                    toast("회원 정보 저장 실패: " + e.getMessage());
                });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
