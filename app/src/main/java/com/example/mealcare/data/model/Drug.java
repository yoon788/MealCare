package com.example.mealcare.data.model;
import com.google.firebase.Timestamp;
import java.util.List;

public class Drug {
    private String drugId; //식별자, 내부 약품 ID
    private String productName; //제품명
    private String companyName; //업체명
    private List<Ingredient> ingredients; //성분 목록, NULL 가능
    private String prescriptionType; //전문의약품인지, 일반의약품인지
    private String source; //약품 정보 출처
    private String sourceItemId; //식약처 품목기준코드
    private String catalogStatus; //약품 목록 상태
    private Timestamp createdAt; //약품 문서의 최초 등록 시각
    private Timestamp updatedAt; //마지막 수정 시각

    public Drug() {}

    public String getDrugId() {
        return drugId;
    }

    public void setDrugId(String drugId) {
        this.drugId = drugId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public List<Ingredient> getIngredients() {
        return ingredients;
    }

    public void setIngredients(List<Ingredient> ingredients) {
        this.ingredients = ingredients;
    }

    public String getPrescriptionType() {
        return prescriptionType;
    }

    public void setPrescriptionType(String prescriptionType) {
        this.prescriptionType = prescriptionType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceItemId() {
        return sourceItemId;
    }

    public void setSourceItemId(String sourceItemId) {
        this.sourceItemId = sourceItemId;
    }

    public String getCatalogStatus() {
        return catalogStatus;
    }

    public void setCatalogStatus(String catalogStatus) {
        this.catalogStatus = catalogStatus;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}