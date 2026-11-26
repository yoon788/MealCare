package com.example.mealcare.data;

import com.example.mealcare.data.model.Ingredient;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DrugStrengthNoteTest {
    private static final String TAGRISSO =
            "총량 : 이 약 1정 (약 520mg) 당|성분명 : 오시머티닙메실산염|분량 : 95.4|단위 : 밀리그램|규격 : 별규|성분정보 : 오시머티닙으로서 80mg";

    @Test
    public void keepsSaltAmountAndEquivalentStrengthTogether() {
        Ingredient salt = ingredient("오시머티닙메실산염", 95.4);
        DrugAPI.applyStrengthNotes(Collections.singletonList(salt), TAGRISSO);
        assertEquals(95.4, salt.getAmount(), 0);
        assertEquals("오시머티닙으로서 80mg", salt.getStrengthNote());
    }

    @Test
    public void matchesCombinationIngredientsEvenInReverseOrder() {
        Ingredient tipiracil = ingredient("티피라실염산염", 7.065);
        Ingredient trifluridine = ingredient("트리플루리딘", 15);
        DrugAPI.applyStrengthNotes(Arrays.asList(tipiracil, trifluridine),
                "총량 : 1정122.703밀리그램|성분명 : 트리플루리딘|분량 : 15.000|단위 : 밀리그램|규격 : 별규|성분정보 : ;"
                        + "총량 : 1정122.703밀리그램|성분명 : 티피라실염산염|분량 : 7.065|단위 : 밀리그램|규격 : 별규|성분정보 : 티피라실로서 6.14mg");
        assertEquals("티피라실로서 6.14mg", tipiracil.getStrengthNote());
        assertNull(trifluridine.getStrengthNote());
    }

    @Test
    public void skipsMissingMismatchedAndAmbiguousNotes() {
        Ingredient salt = ingredient("오시머티닙메실산염", 95.4);
        List<Ingredient> ingredients = Collections.singletonList(salt);
        DrugAPI.applyStrengthNotes(ingredients, null);
        DrugAPI.applyStrengthNotes(ingredients, TAGRISSO.replace("95.4", "47.7"));
        DrugAPI.applyStrengthNotes(ingredients, TAGRISSO.replace("단위 : 밀리그램", "단위 : 그램"));
        DrugAPI.applyStrengthNotes(ingredients, TAGRISSO + ";" + TAGRISSO);
        DrugAPI.applyStrengthNotes(Arrays.asList(salt, ingredient(salt.getName(), 95.4)), TAGRISSO);
        assertNull(salt.getStrengthNote());
        salt.setStrengthNote("주성분 API 원문");
        DrugAPI.applyStrengthNotes(ingredients, TAGRISSO);
        assertEquals("주성분 API 원문", salt.getStrengthNote());
    }

    private static Ingredient ingredient(String name, double amount) {
        Ingredient ingredient = new Ingredient();
        ingredient.setName(name);
        ingredient.setAmount(amount);
        ingredient.setUnit("밀리그램");
        return ingredient;
    }
}
