package com.example.mealcare;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

//Firebase
import android.util.Log;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;


public class FirebaseTestActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //잘 실행된다. 실행 -> log -> FirebaseTest 확인
        FirebaseFirestore.getInstance()
                .document("connection_test/ping")
                .get(Source.SERVER)
                .addOnSuccessListener(doc ->
                        Log.d("FirebaseTest", "서버 조회 성공"))
                .addOnFailureListener(e ->
                        Log.e("FirebaseTest", "서버 조회 실패", e));
    }

}
