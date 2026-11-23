package com.example.mealcare;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/** 2.1 회원가입: Authentication 계정 생성 + Firestore users/{uid}에 이름·역할 저장 */
public class SignUpActivity extends AppCompatActivity {

    private EditText etEmail, etPassword, etPasswordConfirm, etName;
    private RadioGroup rgRole;
    private Button btnSignUp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_up);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etPasswordConfirm = findViewById(R.id.etPasswordConfirm);
        etName = findViewById(R.id.etName);
        rgRole = findViewById(R.id.rgRole);
        btnSignUp = findViewById(R.id.btnSignUp);

        btnSignUp.setOnClickListener(v -> signUp());
    }

    private void signUp() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etPasswordConfirm.getText().toString();
        String name = etName.getText().toString().trim();
        int checkedId = rgRole.getCheckedRadioButtonId();

        // 입력 검사
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password) || TextUtils.isEmpty(name)) {
            toast("모든 칸을 입력을 주세요.");
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("이메일 형식이 올바르지 않습니다.");
            return;
        }
        if (password.length() < 6) {
            toast("비밀번호는 6자 이상으로 설정 가능합니다.");
            return;
        }
        if (!password.equals(confirm)) {
            toast("비밀번호가 서로 다릅니다.");
            return;
        }
        if (checkedId == -1) {
            toast("환자 / 보호자 중 하나를 선택해 주세요.");
            return;
        }
        String role = (checkedId == R.id.rbGuardian) ? "guardian" : "patient";

        btnSignUp.setEnabled(false);
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> saveUser(result.getUser(), name, email, role))
                .addOnFailureListener(e -> {
                    btnSignUp.setEnabled(true);
                    if (e instanceof FirebaseAuthUserCollisionException) {
                        toast("이미 가입된 이메일입니다.");
                    } else if (e instanceof FirebaseAuthWeakPasswordException) {
                        toast("비밀번호가 취약합니다.");
                    } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
                        toast("이메일 형식이 올바르지 않습니다.");
                    } else {
                        toast("가입 실패: " + e.getMessage());
                    }
                });
    }

    private void saveUser(FirebaseUser user, String name, String email, String role) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("email", email);
        data.put("role", role);
        data.put("createdAt", FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance()
                .collection("users").document(user.getUid())
                .set(data)
                .addOnSuccessListener(unused -> {
                    toast("가입 완료!");
                    AuthRouter.goHome(this, user.getUid(), () -> btnSignUp.setEnabled(true));
                })
                .addOnFailureListener(e -> {
                    // users 문서 저장에 실패하면 역할 없는 계정이 남지 않도록 계정도 지운다
                    user.delete();
                    btnSignUp.setEnabled(true);
                    toast("가입 실패: " + e.getMessage());
                });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
