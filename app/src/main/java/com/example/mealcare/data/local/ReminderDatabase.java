package com.example.mealcare.data.local;

import android.content.Context;

import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {ReminderPlan.class, ReminderDoseSlot.class, ReminderMealTime.class},
        version = 2, autoMigrations = @AutoMigration(from = 1, to = 2))
public abstract class ReminderDatabase extends RoomDatabase {
    private static ReminderDatabase instance;

    public static synchronized ReminderDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                    ReminderDatabase.class, "reminders.db").build();
        }
        return instance;
    }

    public abstract ReminderDao reminders();
}
