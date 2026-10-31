package com.example.smarttennisapp.database;

import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.annotation.NonNull;
import androidx.room.PrimaryKey;
@Entity
public class Usuario {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String nome;
    public String email;
    // Armazena hash PBKDF2 local; a senha original nao deve ser persistida.
    public String senha;
    public String data;

    @NonNull
    @ColumnInfo(defaultValue = "'avatar_01'")
    public String avatarId = "avatar_01";
}
