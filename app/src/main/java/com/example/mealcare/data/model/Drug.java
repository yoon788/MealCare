package com.example.mealcare.data.model;
import com.google.firebase.Timestamp;
import java.util.List;

/**
 * 여러 환자가 함께 참조하는 약품 한 품목입니다.
 * 새 복약 계획에는 식약처 품목기준코드인 {@code sourceItemId}와 등록 당시의 {@code productName}을 복사합니다.
 * 검색 목록에서는 기본 정보만 채워지며, 상세 조회가 끝나면 주성분 목록까지 읽을 수 있습니다.
 */
public class Drug {
    private String drugId; //식별자, 내부 약품 ID
    private String productName; //제품명
    private String companyName; //업체명
    private List<Ingredient> ingredients; //성분 목록, NULL 가능
    private String materialNameRaw;
    private String prescriptionType; //전문의약품인지, 일반의약품인지
    private String therapeuticClass;
    private String storageMethod;
    private String shelfLife;
    private String packagingUnit;
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

    /** 검색만 한 상태에서는 null이며, 상세 조회 결과에 주성분이 없으면 빈 목록입니다. */
    public List<Ingredient> getIngredients() {
        return ingredients;
    }

    public void setIngredients(List<Ingredient> ingredients) {
        this.ingredients = ingredients;
    }

    /**
     * 식약처 허가 상세의 MATERIAL_NAME 전체 문자열입니다. 공백과 구분자도 그대로 보존합니다.
     * 검색만 했거나 상세 응답에 원문이 없으면 null입니다. 성분별 값은 {@link #getIngredients()}로 읽습니다.
     */
    public String getMaterialNameRaw() {
        return materialNameRaw;
    }

    public void setMaterialNameRaw(String materialNameRaw) {
        this.materialNameRaw = materialNameRaw;
    }

    public String getPrescriptionType() {
        return prescriptionType;
    }

    public void setPrescriptionType(String prescriptionType) {
        this.prescriptionType = prescriptionType;
    }

    /** 식약처의 약효분류명(예: 항히스타민제)입니다. 분류명을 알 수 없으면 null입니다. */
    public String getTherapeuticClass() {
        return therapeuticClass;
    }

    public void setTherapeuticClass(String therapeuticClass) {
        this.therapeuticClass = therapeuticClass;
    }

    /** 식약처 허가 상세정보의 보관방법 원문입니다. */
    public String getStorageMethod() {
        return storageMethod;
    }

    public void setStorageMethod(String storageMethod) {
        this.storageMethod = storageMethod;
    }

    /** 제조일 기준 사용기간 등 식약처가 제공하는 유효기간 원문입니다. */
    public String getShelfLife() {
        return shelfLife;
    }

    public void setShelfLife(String shelfLife) {
        this.shelfLife = shelfLife;
    }

    /** 허가된 포장단위 원문입니다. */
    public String getPackagingUnit() {
        return packagingUnit;
    }

    public void setPackagingUnit(String packagingUnit) {
        this.packagingUnit = packagingUnit;
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
