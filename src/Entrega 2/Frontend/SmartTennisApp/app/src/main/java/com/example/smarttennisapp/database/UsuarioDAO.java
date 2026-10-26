package com.example.smarttennisapp.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface UsuarioDAO {
    @Insert
    void insert(Usuario usuario);

    @Query("SELECT * FROM Usuario WHERE id = :id")
    Usuario getUserById(int id);

    @Query("SELECT id FROM Usuario WHERE nome = :nome")
    int getIdByName(String nome);

    @Query("SELECT id FROM Usuario WHERE email = :email LIMIT 1")
    int getIdByEmail(String email);

    @Update
    void update(Usuario usuario);
}
