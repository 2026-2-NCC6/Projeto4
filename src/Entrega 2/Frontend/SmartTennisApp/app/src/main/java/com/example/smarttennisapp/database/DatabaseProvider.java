package com.example.smarttennisapp.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Room;

/** Instância única do Room para todo o aplicativo. */
public final class DatabaseProvider {

    private static volatile AppDatabase instance;

    private DatabaseProvider() { }

    public static AppDatabase getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (DatabaseProvider.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "SmartTennisApp"
                            )
                            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
                            .build();
                }
            }
        }
        return instance;
    }
}
