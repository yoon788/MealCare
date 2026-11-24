package com.example.mealcare.data;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.example.mealcare.BuildConfig;
import com.example.mealcare.data.model.Drug;
import com.example.mealcare.data.model.Ingredient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 식약처 의약품 제품 허가 API의 응답을 앱의 {@link Drug}와 {@link Ingredient}로 변환합니다.
 * {@link #searchByName(String)}으로 검색하고, 사용자가 고른 약을 {@link #getDetails(Drug)}에 넘기세요.
 * 조회 메서드는 네트워크 응답을 기다리므로 백그라운드 스레드에서 호출해야 합니다.
 */
public class DrugAPI {
    //의약품 제품 허가 목록 조회
    private final String URL_DRUG = "https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnInq08";

    //허가받은 의약품의 품목, 주성분, 제조원, 포장단위, 저장방법, 성상등의 품목정보와 허가일자, 허가번호, 희귀의약품 여부 등 상세정보로 제공
    private final String URL_DRUG_DETAIL = "https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtPrmsnDtlInq08";

    //의약품 제품 주성분 상세정보를 목록으로 조회
    private final String URL_DRUG_DETAIL_INGREDIENT = "https://apis.data.go.kr/1471000/DrugPrdtPrmsnInfoService08/getDrugPrdtMcpnDtlInq08";

    /** HTTP 오류와 JSON 형식 오류를 호출자에게 전달합니다. URL에는 인증키가 포함됩니다. */
    private static JSONObject getJson(@NonNull Uri url) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP 응답 코드: " + status);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line).append('\n');
                }
                return new JSONObject(body.toString());
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 인증키와 페이지 번호를 붙이고, HTTP 성공 응답 안에 담긴 식약처 오류 코드도 확인합니다. */
    private JSONObject requestBody(String endpoint, String param, String value, int page)
            throws IOException, JSONException {
        if (BuildConfig.MFDS_API_KEY.isEmpty()) {
            throw new IOException("식약처 인증키를 설정하세요.");
        }
        Uri uri = Uri.parse(endpoint).buildUpon()
                .appendQueryParameter("serviceKey", BuildConfig.MFDS_API_KEY)
                .appendQueryParameter("type", "json")
                .appendQueryParameter("pageNo", Integer.toString(page))
                .appendQueryParameter(param, value)
                .build();

        JSONObject response = getJson(uri);
        String code = response.getJSONObject("header").getString("resultCode");
        if (!"00".equals(code)) {
            throw new IOException("식약처 검색 실패: " + code);
        }
        return response.getJSONObject("body");
    }

    /**
     * 제품명으로 검색합니다. 예를 들어 {@code searchByName("타이레놀")}로 후보를 가져옵니다.
     * @return 첫 페이지의 제품명·업체명·전문/일반 구분·약효분류명·품목기준코드를 담은 목록.
     *         일치하는 제품이 없으면 빈 목록이며, 주성분과 보관방법은 상세 조회에서 채웁니다.
     * @throws IOException 네트워크 요청이나 식약처 인증·조회에 실패한 경우
     * @throws JSONException 응답이 예상한 JSON 형식과 다른 경우
     */
    @WorkerThread
    public List<Drug> searchByName(String keyword) throws IOException, JSONException {
        JSONObject body = requestBody(URL_DRUG, "item_name", keyword, 1);
        List<Drug> drugs = new ArrayList<>();
        if (body.getInt("totalCount") == 0) return drugs;

        JSONArray items = body.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            Drug drug = readDrug(item, "SPCLTY_PBLC");
            String productType = optionalText(item, "PRDUCT_TYPE");
            if (productType != null) {
                // 예: "[01410]항히스타민제"에서 화면에 표시할 분류명만 읽습니다.
                String name = productType.trim().replaceFirst("^\\[[0-9]+\\]", "").trim();
                drug.setTherapeuticClass(name.isEmpty() ? null : name);
            }
            drugs.add(drug);
        }
        return drugs;
    }

    /**
     * 검색 결과에서 선택한 약의 품목기준코드로 상세 정보를 조회합니다.
     * 약효분류명은 허가 목록에서 제공하므로 선택한 약의 값을 새 상세 객체에 복사합니다.
     * @return 상세 정보와 주성분을 채운 새 Drug. 식약처에 해당 품목이 없으면 null입니다.
     * @throws IllegalArgumentException 선택한 약에 품목기준코드가 없는 경우
     * @throws IOException 네트워크 요청이나 식약처 조회에 실패한 경우
     * @throws JSONException 응답이 예상한 JSON 형식과 다른 경우
     */
    @Nullable
    @WorkerThread
    public Drug getDetails(@NonNull Drug selectedDrug) throws IOException, JSONException {
        Drug detail = getDetails(selectedDrug.getSourceItemId());
        if (detail != null) {
            detail.setTherapeuticClass(selectedDrug.getTherapeuticClass());
        }
        return detail;
    }

    /**
     * 품목기준코드로 허가 상세와 전체 주성분을 조회합니다.
     * 코드만으로 조회하면 약효분류명은 null입니다. 분류명도 필요하면 {@link #getDetails(Drug)}를 사용하세요.
     * @param sourceItemId 검색 결과의 {@link Drug#getSourceItemId()} (식약처 품목기준코드)
     * @return 보관방법·사용기간·포장단위와 전체 주성분을 담은 약품. 해당 품목이 없으면 null.
     *         성분별 값은 {@link Drug#getIngredients()}, 성분 원문은 {@link Drug#getMaterialNameRaw()}로 읽습니다.
     * @throws IllegalArgumentException 품목기준코드가 비어 있는 경우
     * @throws IOException 상세 또는 성분 조회에 실패한 경우. 일부만 채운 약품은 반환하지 않습니다.
     * @throws JSONException 응답이 예상한 JSON 형식과 다른 경우
     */
    @Nullable
    @WorkerThread
    public Drug getDetails(String sourceItemId) throws IOException, JSONException {
        String id = requireItemId(sourceItemId);
        JSONObject body = requestBody(URL_DRUG_DETAIL, "item_seq", id, 1);
        if (body.getInt("totalCount") == 0) return null;

        JSONObject item = body.getJSONArray("items").getJSONObject(0);
        checkItemId(item, id);
        Drug drug = readDrug(item, "ETC_OTC_CODE");
        drug.setStorageMethod(optionalText(item, "STORAGE_METHOD"));
        drug.setShelfLife(optionalText(item, "VALID_TERM"));
        drug.setPackagingUnit(optionalText(item, "PACK_UNIT"));
        String materialName = optionalText(item, "MATERIAL_NAME");
        drug.setMaterialNameRaw(materialName);
        List<Ingredient> ingredients = readIngredients(id, commonBasis(materialName));
        applyStrengthNotes(ingredients, materialName);
        drug.setIngredients(ingredients);
        return drug;
    }

    /**
     * 선택한 제품의 주성분명·함량·단위를 조회합니다. 결과가 없으면 빈 목록을 반환합니다.
     * 주성분 응답에 함량 설명이 있으면 함께 반환합니다. 허가 상세의 '성분정보'와 함량 기준까지
     * 확인하려면 {@link #getDetails(Drug)}를 사용하세요.
     * @param selectedDrug {@link #searchByName(String)}에서 선택한 식약처 제품
     * @throws IllegalArgumentException 선택한 약에 품목기준코드가 없는 경우
     * @throws IOException 네트워크 요청이나 식약처 조회에 실패한 경우
     * @throws JSONException 응답이 예상한 JSON 형식과 다른 경우
     */
    @WorkerThread
    public List<Ingredient> getIngredients(@NonNull Drug selectedDrug) throws IOException, JSONException {
        return readIngredients(requireItemId(selectedDrug.getSourceItemId()), null);
    }

    private List<Ingredient> readIngredients(String id, @Nullable String basis) throws IOException, JSONException {
        List<Ingredient> ingredients = new ArrayList<>();
        for (int page = 1; ; page++) {
            // 주성분 API는 첫 글자가 대문자인 Item_seq, 허가 상세 API는 소문자인 item_seq를 받습니다.
            JSONObject body = requestBody(URL_DRUG_DETAIL_INGREDIENT, "Item_seq", id, page);
            int total = body.getInt("totalCount");
            if (total == 0 && page == 1) return ingredients;
            JSONArray items = body.getJSONArray("items");
            if (items.length() == 0 || body.getInt("pageNo") != page) {
                throw new IOException("식약처 주성분 목록의 페이지가 누락되었습니다.");
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                checkItemId(item, id);
                ingredients.add(readIngredient(item, basis));
            }
            if (ingredients.size() >= total) return ingredients;
        }
    }

    /** 목록과 상세에 공통인 식약처 필드를 약품 하나에 담습니다. 전문·일반 구분의 필드명은 API마다 다릅니다. */
    private static Drug readDrug(JSONObject item, String categoryField) throws JSONException {
        Drug drug = new Drug();
        drug.setProductName(item.getString("ITEM_NAME"));
        drug.setCompanyName(optionalText(item, "ENTP_NAME"));
        drug.setSourceItemId(item.getString("ITEM_SEQ"));
        drug.setSource("mfds_permit");
        switch (item.optString(categoryField)) {
            case "일반의약품":
                drug.setPrescriptionType("otc");
                break;
            case "전문의약품":
                drug.setPrescriptionType("prescription");
                break;
            default:
                drug.setPrescriptionType("unknown");
        }
        return drug;
    }

    /**
     * 주성분 응답 한 건을 성분명·제품 함량·단위로 옮깁니다.
     * basis는 허가 상세에서 확인한 함량 기준(예: 1정)이며, 확인할 수 없으면 null입니다.
     */
    private static Ingredient readIngredient(JSONObject item, @Nullable String basis) throws JSONException {
        Ingredient ingredient = new Ingredient();
        ingredient.setName(item.getString("MTRAL_NM"));
        // 함량이 비어 있거나 숫자로 표현되지 않으면 null을 유지합니다. 0도 유효한 입력값입니다.
        double amount = item.optDouble("QNT");
        if (Double.isFinite(amount)) {
            ingredient.setAmount(amount);
        }
        ingredient.setUnit(optionalText(item, "INGD_UNIT_CD"));
        ingredient.setBasis(basis);
        String note = optionalText(item, "CPNT_CTNT_CONT");
        if (note != null && !note.trim().isEmpty()) {
            ingredient.setStrengthNote(note.trim());
        }
        return ingredient;
    }

    /**
     * 주성분 응답에 빠진 함량 설명을 허가 상세(MATERIAL_NAME)의 '성분정보'에서 보완합니다.
     * 두 응답의 행 순서는 다를 수 있어 성분명·분량·단위가 일치하는 행이 하나일 때만 연결합니다.
     */
    static void applyStrengthNotes(List<Ingredient> ingredients, @Nullable String materialName) {
        if (materialName == null) return;
        Map<Ingredient, String> matches = new HashMap<>();
        for (String row : materialName.split(";\\s*(?=총량\\s*:)")) {
            Map<String, String> fields = new HashMap<>();
            for (String field : row.split("\\|")) {
                int colon = field.indexOf(':');
                if (colon >= 0) {
                    fields.put(field.substring(0, colon).trim(), field.substring(colon + 1).trim());
                }
            }
            Ingredient match = matchingIngredient(ingredients, fields);
            if (match != null && match.getStrengthNote() == null) {
                // 같은 성분에 두 허가 행이 대응하면 어느 설명이 맞는지 확정할 수 없습니다.
                matches.put(match, matches.containsKey(match) ? null : fields.get("성분정보"));
            }
        }
        for (Map.Entry<Ingredient, String> match : matches.entrySet()) {
            String note = match.getValue();
            if (note != null && !note.isEmpty()) {
                match.getKey().setStrengthNote(note);
            }
        }
    }

    @Nullable
    private static Ingredient matchingIngredient(List<Ingredient> ingredients, Map<String, String> fields) {
        double amount;
        try {
            amount = Double.parseDouble(fields.getOrDefault("분량", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
        Ingredient match = null;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.getAmount() != null && ingredient.getAmount() == amount
                    && ingredient.getUnit() != null && ingredient.getUnit().equals(fields.get("단위"))
                    && ingredient.getName() != null && ingredient.getName().equals(fields.get("성분명"))) {
                if (match != null) return null;
                match = ingredient;
            }
        }
        return match;
    }

    private static String requireItemId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("식약처 품목기준코드가 필요합니다.");
        }
        return value.trim();
    }

    private static void checkItemId(JSONObject item, String id) throws IOException, JSONException {
        if (!id.equals(item.getString("ITEM_SEQ"))) {
            throw new IOException("식약처 응답의 품목기준코드가 다릅니다.");
        }
    }

    @Nullable
    private static String optionalText(JSONObject item, String key) throws JSONException {
        return item.isNull(key) ? null : item.getString(key);
    }

    // 허가 상세의 '총량 : 이 약 75mL 중|...'에서 함량 기준을 읽습니다.
    // 한 품목 안에서 기준이 서로 다르면 특정 기준을 모든 성분에 잘못 붙이지 않도록 null을 반환합니다.
    @Nullable
    private static String commonBasis(@Nullable String materialName) {
        if (materialName == null) return null;
        String basis = null;
        for (String row : materialName.split(";")) {
            int colon = row.indexOf(':');
            int end = row.indexOf('|');
            if (colon < 0 || end <= colon || !row.substring(0, colon).trim().equals("총량")) return null;
            String value = row.substring(colon + 1, end).trim();
            if (value.isEmpty() || (basis != null && !basis.equals(value))) return null;
            basis = value;
        }
        return basis;
    }

}
