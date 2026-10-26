package com.example.smarttennisapp.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TreinoDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void salvarTreinoOffline(TreinoOfflineEntity treino);

    @Query("SELECT * FROM treinos_offline WHERE id = :treinoId LIMIT 1")
    TreinoOfflineEntity buscarTreino(String treinoId);

    @Insert
    void salvarHistorico(HistoricoTreinoEntity historico);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void salvarHistoricoSincronizado(HistoricoTreinoEntity historico);

    @Query("SELECT * FROM historico_treinos WHERE syncId = :syncId LIMIT 1")
    HistoricoTreinoEntity buscarPorSyncId(String syncId);

    @Query("UPDATE historico_treinos SET syncId = :syncId WHERE id = :id")
    void atualizarSyncId(long id, String syncId);

    @Query(
            "SELECT * FROM historico_treinos " +
                    "WHERE usuarioId = :usuarioId AND treinoId = :treinoId " +
                    "ORDER BY pontuacao DESC, tempoSegundos ASC LIMIT 1"
    )
    HistoricoTreinoEntity buscarMelhorResultado(
            int usuarioId,
            String treinoId
    );

    @Query("SELECT * FROM historico_treinos WHERE usuarioId = :usuarioId AND treinoId = :treinoId ORDER BY dataConclusao DESC")
    List<HistoricoTreinoEntity> listarHistorico(int usuarioId, String treinoId);

    @Query("SELECT * FROM historico_treinos WHERE usuarioId = :usuarioId AND treinoId = :treinoId ORDER BY dataConclusao DESC LIMIT 1")
    HistoricoTreinoEntity buscarUltimaExecucao(int usuarioId, String treinoId);

    /** Histórico completo do usuário, usado pelo painel de Estatísticas. */
    @Query("SELECT * FROM historico_treinos WHERE usuarioId = :usuarioId ORDER BY dataConclusao DESC")
    List<HistoricoTreinoEntity> listarHistoricoUsuario(int usuarioId);

    @Query("SELECT COUNT(*) FROM historico_treinos WHERE usuarioId = :usuarioId AND treinoId = :treinoId")
    int contarExecucoes(int usuarioId, String treinoId);
}