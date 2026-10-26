package com.example.smarttennisapp.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                Usuario.class,
                TreinoOfflineEntity.class,
                HistoricoTreinoEntity.class,
                DesafioEntity.class
        },
        version = 6,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    /** Cria somente a nova tabela e preserva usuários e histórico já existentes. */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `desafios_personalizados` (" +
                            "`id` TEXT NOT NULL, " +
                            "`usuarioId` INTEGER NOT NULL, " +
                            "`nome` TEXT, " +
                            "`descricao` TEXT, " +
                            "`duracaoMinutos` INTEGER NOT NULL, " +
                            "`dificuldade` TEXT, " +
                            "`categoria` TEXT, " +
                            "`sequenciaGolpes` TEXT, " +
                            "`criadoEm` INTEGER NOT NULL, " +
                            "`atualizadoEm` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_desafios_personalizados_usuarioId` " +
                            "ON `desafios_personalizados` (`usuarioId`)"
            );
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `totalGolpes` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `golpesRecebidos` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `concluido` INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** Metas da webcam no desafio e métricas de bola no histórico; valores antigos ficam em zero. */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `desafios_personalizados` ADD COLUMN `velocidadeAlvoKmh` REAL NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `desafios_personalizados` ADD COLUMN `bolasAlvo` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `bolasDetectadas` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `bolasAcimaMeta` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `velocidadeMaximaKmh` REAL NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `velocidadeMediaKmh` REAL NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `metricasCameraJson` TEXT NOT NULL DEFAULT '[]'");
        }
    };

    /** UUID de historico e exclusao logica de desafios para sincronizacao entre aparelhos. */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `syncId` TEXT NOT NULL DEFAULT ''");
            database.execSQL("ALTER TABLE `desafios_personalizados` ADD COLUMN `excluido` INTEGER NOT NULL DEFAULT 0");
        }
    };


    /** Armazena as métricas por classe do ESP32 sem descartar os históricos antigos. */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `historico_treinos` ADD COLUMN `metricasGolpesJson` TEXT NOT NULL DEFAULT '{}'");
        }
    };

    public abstract UsuarioDAO usuarioDAO();
    public abstract TreinoDAO treinoDAO();
    public abstract DesafioDAO desafioDAO();
}
