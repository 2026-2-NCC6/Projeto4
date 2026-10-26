package com.example.smarttennisapp.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "treinos_offline")
public class TreinoOfflineEntity {

    @PrimaryKey
    @NonNull
    public String id;

    public String nome;
    public String descricao;
    public int duracaoMinutos;
    public String dificuldade;
    public String categoria;

    // Exemplo: SAQUE,FOREHAND,BACKHAND
    public String sequenciaGolpes;

    public TreinoOfflineEntity(
            @NonNull String id,
            String nome,
            String descricao,
            int duracaoMinutos,
            String dificuldade,
            String categoria,
            String sequenciaGolpes
    ) {
        this.id = id;
        this.nome = nome;
        this.descricao = descricao;
        this.duracaoMinutos = duracaoMinutos;
        this.dificuldade = dificuldade;
        this.categoria = categoria;
        this.sequenciaGolpes = sequenciaGolpes;
    }
}