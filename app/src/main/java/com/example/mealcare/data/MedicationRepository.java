package com.example.mealcare.data;

import com.example.mealcare.Medication;
import com.google.android.gms.tasks.Task;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

public class MedicationRepository {
    private final CollectionReference col;

    public MedicationRepository(String patientUid) {
        col = FirebaseFirestore.getInstance()
                .collection("users").document(patientUid)
                .collection("medications");
    }

    public Task<DocumentReference> add(Medication m) {
        return col.add(m);
    }

    public Task<Void> update(Medication m) {
        return col.document(m.id).set(m);
    }

    public Task<Void> delete(String id) {
        return col.document(id).delete();
    }

    public Task<QuerySnapshot> getAll() {
        return col.get();
    }
}
