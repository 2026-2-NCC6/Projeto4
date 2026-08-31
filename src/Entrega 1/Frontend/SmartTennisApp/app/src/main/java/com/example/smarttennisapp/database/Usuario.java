package com.example.smarttennisapp.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity
public class Usuario {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String nome;
    public String email;
    //NÃO ARMAZENAR A SENHA AQUI!!
    public String senha;
}
