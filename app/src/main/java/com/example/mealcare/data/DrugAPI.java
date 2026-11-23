package com.example.mealcare.data;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

//작동되는지 모름. 아직 테스트 안함. 인증키는 깃에 올리지 않음. 따라서 작동되지 않을 예정

public class DrugAPI {
    private static final String ENDPOINT = "https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService08";

    public JSONObject searchRaw(String keyword, String decodingKey)
            throws IOException, JSONException {
        Uri uri = Uri.parse(ENDPOINT).buildUpon()
                .appendQueryParameter("serviceKey", decodingKey)
                .appendQueryParameter("item_name", keyword)
                .appendQueryParameter("type", "json")
                .appendQueryParameter("pageNo", "1")
                .appendQueryParameter("numOfRows", "20")
                .build();

        HttpURLConnection connection =
                (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(false);

        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("식약처 API 응답 상태: " + status);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                return new JSONObject(json.toString());
            }
        } finally {
            connection.disconnect();
        }
    }
}