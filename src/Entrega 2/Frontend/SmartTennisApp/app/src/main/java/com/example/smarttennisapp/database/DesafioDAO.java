package com.example.smarttennisapp.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DesafioDAO {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void salvar(DesafioEntity desafio);

    @Query("SELECT * FROM desafios_personalizados WHERE id = :id AND usuarioId = :usuarioId AND excluido = 0 LIMIT 1")
    DesafioEntity buscarPorId(String id, int usuarioId);

    /** Busca incluindo excluidos, usado somente pela sincronizacao. */
    @Query("SELECT * FROM desafios_personalizados WHERE id = :id AND usuarioId = :usuarioId LIMIT 1")
    DesafioEntity buscarParaSincronizacao(String id, int usuarioId);

    /** Retorna ativos e excluidos para enviar tombstones ao servidor. */
    @Query("SELECT * FROM desafios_personalizados WHERE usuarioId = :usuarioId ORDER BY atualizadoEm ASC")
    List<DesafioEntity> listarTodosParaSincronizacao(int usuarioId);

    @Query("SELECT * FROM desafios_personalizados WHERE usuarioId = :usuarioId AND excluido = 0 ORDER BY atualizadoEm DESC, nome COLLATE NOCASE ASC")
    List<DesafioEntity> listarPorUsuario(int usuarioId);

    @Query("UPDATE desafios_personalizados SET excluido = 1, atualizadoEm = MAX(atualizadoEm + 1, CAST(strftime('%s','now') AS INTEGER) * 1000) WHERE id = :id AND usuarioId = :usuarioId AND excluido = 0")
    int excluir(String id, int usuarioId);
}
