package com.example.smarttennisapp.database;

import androidx.room.Entity;
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
}
