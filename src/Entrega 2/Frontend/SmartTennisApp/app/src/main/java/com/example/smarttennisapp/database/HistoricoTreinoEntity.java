package com.example.smarttennisapp.database;

import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;
import java.util.UUID;

@Entity(tableName = "historico_treinos")
public class HistoricoTreinoEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Identificador estavel para nao duplicar historicos entre dispositivos. */
    @NonNull
    @ColumnInfo(defaultValue = "''")
    public String syncId = UUID.randomUUID().toString();

    public int usuarioId;
    public String treinoId;
    public long dataConclusao;

    public int tempoSegundos;
    public int pontuacao;

    public int saques;
    public int forehands;
    public int backhands;
    public int golpesCorretos;

    @ColumnInfo(defaultValue = "0")
    public int totalGolpes;

    @ColumnInfo(defaultValue = "0")
    public int golpesRecebidos;

    @ColumnInfo(defaultValue = "0")
    public boolean concluido;

    @ColumnInfo(defaultValue = "0")
    public int bolasDetectadas;

    @ColumnInfo(defaultValue = "0")
    public int bolasAcimaMeta;

    @ColumnInfo(defaultValue = "0")
    public float velocidadeMaximaKmh;

    @ColumnInfo(defaultValue = "0")
    public float velocidadeMediaKmh;

    @ColumnInfo(defaultValue = "'[]'")
    public String metricasCameraJson = "[]";

    /** Contagens e métricas por classe do detector ESP32 v2 (independente da câmera). */
    @ColumnInfo(defaultValue = "'{}'")
    public String metricasGolpesJson = "{}";

    public HistoricoTreinoEntity(
            int usuarioId,
            String treinoId,
            long dataConclusao,
            int tempoSegundos,
            int pontuacao,
            int saques,
            int forehands,
            int backhands,
            int golpesCorretos
    ) {
        this.usuarioId = usuarioId;
        this.treinoId = treinoId;
        this.dataConclusao = dataConclusao;
        this.tempoSegundos = tempoSegundos;
        this.pontuacao = pontuacao;
        this.saques = saques;
        this.forehands = forehands;
        this.backhands = backhands;
        this.golpesCorretos = golpesCorretos;
    }

    public HistoricoTreinoEntity() {

    }
}