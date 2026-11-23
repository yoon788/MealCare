package com.example.mealcare;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/** 2.1 로그인 + 자동 로그인 */
public class LoginActivity extends AppCompatActivity {

    private FirebaseAuth auth;
    private EditText etEmail, etPassword;
    private Button btnLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        auth = FirebaseAuth.getInstance();
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> login());
        findViewById(R.id.btnGoSignUp).setOnClickListener(v ->
                startActivity(new Intent(this, SignUpActivity.class)));

        // 자동 로그인: Firebase가 로그인 상태를 기기에 기억하고 있으면 바로 홈으로 이동
        FirebaseUser user = auth.getCurrentUser();
        if (user != null) {
            btnLogin.setEnabled(false);
            AuthRouter.goHome(this, user.getUid(), () -> btnLogin.setEnabled(true));
        }
    }

    private void login() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, "이메일과 비밀번호를 입력해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false); // 중복 클릭 방지
        auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(result ->
                        AuthRouter.goHome(this, result.getUser().getUid(), () -> btnLogin.setEnabled(true)))
                .addOnFailureListener(e -> {
                    btnLogin.setEnabled(true);
                    Toast.makeText(this, "이메일 또는 비밀번호가 올바르지 않습니다.", Toast.LENGTH_SHORT).show();
                });
    }
}
