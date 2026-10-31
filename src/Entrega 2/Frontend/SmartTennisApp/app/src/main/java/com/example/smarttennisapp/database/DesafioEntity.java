package com.example.smarttennisapp.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Definição persistente de um desafio criado pelo usuário.
 * A sequência de golpes é armazenada na ordem exata em que deve ser executada,
 * por exemplo: SAQUE,FOREHAND,BACKHAND,FOREHAND.
 */
@Entity(tableName = "desafios_personalizados", indices = {@Index(value = {"usuarioId"})})
public class DesafioEntity {

    @PrimaryKey
    @NonNull
    public String id;

    public int usuarioId;
    public String nome;
    public String descricao;
    public int duracaoMinutos;
    public String dificuldade;
    public String categoria;
    public String sequenciaGolpes;
    public long criadoEm;
    public long atualizadoEm;

    @ColumnInfo(defaultValue = "0")
    public float velocidadeAlvoKmh;

    @ColumnInfo(defaultValue = "0")
    public int bolasAlvo;

    /** Integração opcional com a estação local de visão computacional. */
    @ColumnInfo(defaultValue = "0")
    public boolean usaCamera = false;

    /** Exclusao logica para propagar remocoes aos outros celulares na sincronizacao. */
    @ColumnInfo(defaultValue = "0")
    public boolean excluido = false;

    public DesafioEntity(
            @NonNull String id, int usuarioId, String nome, String descricao,
            int duracaoMinutos, String dificuldade, String categoria,
            String sequenciaGolpes, long criadoEm, long atualizadoEm,
            float velocidadeAlvoKmh, int bolasAlvo, boolean usaCamera
    ) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.nome = nome;
        this.descricao = descricao;
        this.duracaoMinutos = duracaoMinutos;
        this.dificuldade = dificuldade;
        this.categoria = categoria;
        this.sequenciaGolpes = sequenciaGolpes;
        this.criadoEm = criadoEm;
        this.atualizadoEm = atualizadoEm;
        this.velocidadeAlvoKmh = velocidadeAlvoKmh;
        this.bolasAlvo = bolasAlvo;
        this.usaCamera = usaCamera;
    }
}
