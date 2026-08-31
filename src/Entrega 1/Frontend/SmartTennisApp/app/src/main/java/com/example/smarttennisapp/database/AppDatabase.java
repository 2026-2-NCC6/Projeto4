package com.example.smarttennisapp.database;
import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(
        entities = {Usuario.class},
        version = 1
)
public abstract class AppDatabase extends RoomDatabase{
    public abstract UsuarioDAO usuarioDAO();
}
