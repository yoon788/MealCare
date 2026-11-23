package com.example.mealcare.testcode;

import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.mealcare.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

//!!경고!! 진짜 로그인 용도로 쓰는 코드 아님. 오히려 로그인 데이터를 데이터베이스에 올리는 코드가 더 맞음!!//
//!!절대 이 코드를 사용해 시제품을 만들지 말 것!!//

//Firebase store의 User에 데이터를 추가하는 코드. 테스트 용이며 지피티로 짬. 어떻게 작동하는지 버튼 빼고 모름
//https://console.firebase.google.com/u/3/project/mealcare-84d8a/authentication/users
//위 링크에 user가 있어야지 데이터가 들어감.
//https://console.firebase.google.com/u/3/project/mealcare-84d8a/firestore/databases/-default-/data/
//Firebase store에 추가된 것을 확인할 수 있음.

public class TestLogIn extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        LinearLayout linear = new LinearLayout(this);
        linear.setOrientation(LinearLayout.VERTICAL);

        EditText emailInput = new EditText(this);
        emailInput.setHint("테스트 이메일");
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        EditText passwordInput = new EditText(this);
        passwordInput.setHint("테스트 비밀번호");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Button bt1 = new Button(this);
        bt1.setText("Test Log in");

        linear.addView(emailInput);
        linear.addView(passwordInput);
        linear.addView(bt1);
        setContentView(linear);

        bt1.setOnClickListener(v -> {
            String email;
            email = emailInput.getText().toString().trim();
            String password = passwordInput.getText().toString();

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "이메일과 비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show();
                return;
            }

            FirebaseAuth.getInstance()
                    .signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener(result -> createTestUserDocuments())
                    .addOnFailureListener(error -> Log.e("TestLogin", "로그인 실패", error));
        });
    }


    private void createTestUserDocuments() {
        FirebaseUser account = FirebaseAuth.getInstance().getCurrentUser();
        if (account == null) {
            Log.w("TestUser", "테스트 계정으로 먼저 로그인하세요.");
            return;
        }

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String uid = account.getUid();
        DocumentReference userRef = db.collection("users").document(uid);
        DocumentReference profileRef = db.collection("publicProfiles").document(uid);

        Map<String, Object> userFields = new HashMap<>();
        userFields.put("role", "patient");
        userFields.put("status", "active");
        userFields.put("createdAt", FieldValue.serverTimestamp());
        userFields.put("updatedAt", FieldValue.serverTimestamp());

        db.runTransaction(tx -> {
                    boolean userExists = tx.get(userRef).exists();
                    boolean profileExists = tx.get(profileRef).exists();

                    if (!userExists) {
                        tx.set(userRef, userFields);
                    }
                    if (!profileExists) {
                        tx.set(profileRef, Collections.singletonMap("nickname", "테스트 환자"));
                    }
                    return null;
                }).addOnSuccessListener(result -> Log.d("TestUser", "회원 문서 준비 완료"))
                .addOnFailureListener(error -> Log.e("TestUser", "회원 문서 생성 실패", error));
    }
}

// 따봉지피티야 고마워!
//
//                         .--.
//                        /::. \
//                       |:;=. |   .--.
//                      /.:==. |  /+=--\
//                      /.:==:  /+***==\....
//                     /.:===:| \--==--'.........:
//                    /.:==== /.:...  .-.  ............
//                   /.:;=== /:..... (@#)  ...............
//                  /..:===/-;;.....  `-'  .................
//                 /..:;==/+-;....  .----.  ..................::
//                /..:;==/+=--:::: /#%@@%#\ ....  .-.  .. .-===-.
//               /.:;===/**==-:::: \#@@@@#/ :::: (@@)  .. /::*++*)
//              /.:;===/+*++==::::  `----'  ::::  `-'  .. \-=++*/
//              |.:;===_.**+++:::::::    \.   :.........:  `---'
//             /.:;===-++***+:::::::::    `---..'  .....::::..
//            /.:;===-+++***===:::::;;;;::::::::.......:::....
//            ..;:--==-+==========::;;;;;;;;::::::::;..::.....
//           ...;:-==++++==**==***=**;;;;;;;;:::+==-:;.::......
//           ...;-+**+#*+*****##*+++++++****==**+++=--;;.:.....
//          ....;:--;=--+===***+++###++++=====*=++++==-;;;:.....
//         ...:.;;--;=--+==******+++++#++**=====++++**+-;;::....
//         ...:.;;-=**+-++******===****##****====+*+=;:........
//         ..::.;;:--;;=-++++====--=**************+-=;-..........
//        ..:::;;;;-=====-+++-+++*+-=====*********+++=+-;;..::-:.
//        ..:::.;;;::-;;===--;;-**+--======*******    =-;;;;=*=
//        .:::..;;::-;;=====;;;-+##+--+++=*****++:       :-;
//        .:...;;:-=-;====++--;;+##+-;:---=**#=::::      ::..
//       ..;;;.;---;==+--------++#++-;;;:-+*##::::::  ::::::..
//       :;;:::---===+++-++-===**++-;;;;-++%@@:::::::::::::::.
//        -----;;;==-+++**********+-;;;;--+%#@::::::::::::::::
//       :-;;====-----===********++--;;;:-=*#@#:::::::::::::::
//       ::;;=+++++*======*==*===++---::-=**##%:::::::::::::::
//       :::;--++**%#***==============--===+##%%::::::::::::::
//      ::::::--==**#####++++++++======*==+#+###:::::::::::::
//      .:::::::-==**###%####++##++#########++#%:::::::::::::
//     ..:::::::::-==*+##%%%%##%%####%#+##++++##::::::::::::
//    ...::::::::::::;===+#%%%****++**##+++###:::::::::::::.
//    ...::::::::::::::.-;==#%%+#**++++++#+# +:::::::::::..
//  .....:::::::::::::::::::;=#%%%%****#::::::::::::::::..
// ......::::::::::::::::::..:;+%%%%%;;;;;;;;;;;;;;;:....
// .......::::::::::::::::::::::::;;;;;;;;;;;;;:::;::...