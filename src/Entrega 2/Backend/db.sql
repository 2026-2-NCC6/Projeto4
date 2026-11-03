CREATE DATABASE IF NOT EXISTS smarttennis_backend
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE smarttennis_backend;

CREATE TABLE IF NOT EXISTS usuario (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  nome VARCHAR(80) NOT NULL,
  email VARCHAR(254) NOT NULL,
  senha_hash VARCHAR(255) NOT NULL,
  data_nascimento VARCHAR(10) NULL,
  avatar_id VARCHAR(40) NOT NULL DEFAULT 'avatar_01',
  gamificacao_json LONGTEXT NULL,
  criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  atualizado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_usuario_email (email)
) ENGINE=InnoDB;

-- Mantida para compatibilidade com o modelo antigo de exercicios/catalogo.
CREATE TABLE IF NOT EXISTS exercicio (
  id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  titulo VARCHAR(100) NOT NULL,
  autor VARCHAR(80) NULL,
  dificuldade INT NOT NULL DEFAULT 1,
  descricao TEXT NULL,
  duracao_segundos INT UNSIGNED NOT NULL DEFAULT 0,
  dados LONGTEXT NULL,
  objetivo INT NOT NULL DEFAULT 0,
  criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Os desafios personalizados correspondem a desafios_personalizados no Room.
-- IDs sao criados no celular (UUID), portanto sao iguais em todos os aparelhos.
CREATE TABLE IF NOT EXISTS desafio_personalizado (
  id VARCHAR(80) NOT NULL,
  usuario_id INT UNSIGNED NOT NULL,
  nome VARCHAR(120) NOT NULL,
  descricao TEXT NULL,
  duracao_minutos INT UNSIGNED NOT NULL DEFAULT 1,
  dificuldade VARCHAR(40) NULL,
  categoria VARCHAR(60) NULL,
  sequencia_golpes TEXT NULL,
  criado_em_ms BIGINT NOT NULL,
  atualizado_em_ms BIGINT NOT NULL,
  velocidade_alvo_kmh FLOAT NOT NULL DEFAULT 0,
  bolas_alvo INT UNSIGNED NOT NULL DEFAULT 0,
  usa_camera TINYINT(1) NOT NULL DEFAULT 0,
  excluido TINYINT(1) NOT NULL DEFAULT 0,
  compartilhado TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_desafio_usuario_atualizado (usuario_id, atualizado_em_ms),
  KEY idx_desafio_publico (compartilhado, excluido, atualizado_em_ms),
  CONSTRAINT fk_desafio_usuario FOREIGN KEY (usuario_id) REFERENCES usuario(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- sync_id e estavel entre aparelhos, evitando duplicar uma mesma execucao no sync.
CREATE TABLE IF NOT EXISTS historico_treino (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  usuario_id INT UNSIGNED NOT NULL,
  sync_id VARCHAR(36) NOT NULL,
  treino_id VARCHAR(120) NOT NULL,
  data_conclusao_ms BIGINT NOT NULL,
  tempo_segundos INT UNSIGNED NOT NULL DEFAULT 0,
  pontuacao INT NOT NULL DEFAULT 0,
  saques INT UNSIGNED NOT NULL DEFAULT 0,
  forehands INT UNSIGNED NOT NULL DEFAULT 0,
  backhands INT UNSIGNED NOT NULL DEFAULT 0,
  golpes_corretos INT UNSIGNED NOT NULL DEFAULT 0,
  total_golpes INT UNSIGNED NOT NULL DEFAULT 0,
  golpes_recebidos INT UNSIGNED NOT NULL DEFAULT 0,
  concluido TINYINT(1) NOT NULL DEFAULT 0,
  bolas_detectadas INT UNSIGNED NOT NULL DEFAULT 0,
  bolas_acima_meta INT UNSIGNED NOT NULL DEFAULT 0,
  velocidade_maxima_kmh FLOAT NOT NULL DEFAULT 0,
  velocidade_media_kmh FLOAT NOT NULL DEFAULT 0,
  metricas_camera_json LONGTEXT NOT NULL,
  recebido_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_historico_usuario_sync (usuario_id, sync_id),
  KEY idx_historico_usuario_data (usuario_id, data_conclusao_ms),
  KEY idx_historico_ranking (concluido, treino_id, usuario_id, pontuacao, tempo_segundos),
  CONSTRAINT fk_historico_usuario FOREIGN KEY (usuario_id) REFERENCES usuario(id) ON DELETE CASCADE
) ENGINE=InnoDB;


-- Codigos temporarios: um por usuario, oito digitos, validade de cinco minutos.
CREATE TABLE IF NOT EXISTS codigo_amizade (
  codigo CHAR(8) NOT NULL,
  usuario_id INT UNSIGNED NOT NULL,
  expira_em_ms BIGINT NOT NULL,
  criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (codigo),
  UNIQUE KEY uq_codigo_amizade_usuario (usuario_id),
  KEY idx_codigo_amizade_expira (expira_em_ms),
  CONSTRAINT fk_codigo_amizade_usuario FOREIGN KEY (usuario_id) REFERENCES usuario(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Relacoes armazenadas com os IDs em ordem para impedir amizades duplicadas.
CREATE TABLE IF NOT EXISTS amizade (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  usuario_a_id INT UNSIGNED NOT NULL,
  usuario_b_id INT UNSIGNED NOT NULL,
  criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_amizade_par (usuario_a_id, usuario_b_id),
  KEY idx_amizade_b (usuario_b_id),
  CONSTRAINT fk_amizade_usuario_a FOREIGN KEY (usuario_a_id) REFERENCES usuario(id) ON DELETE CASCADE,
  CONSTRAINT fk_amizade_usuario_b FOREIGN KEY (usuario_b_id) REFERENCES usuario(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Tabela antiga de progresso por exercicio, mantida para evolucao futura.
CREATE TABLE IF NOT EXISTS usuarioexercicio (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  usuario_id INT UNSIGNED NOT NULL,
  exercicio_id INT UNSIGNED NOT NULL,
  acertos FLOAT NOT NULL DEFAULT 0,
  erros FLOAT NOT NULL DEFAULT 0,
  nota FLOAT NOT NULL DEFAULT 0,
  tempo_segundos INT UNSIGNED NOT NULL DEFAULT 0,
  dados LONGTEXT NULL,
  criado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_usuarioexercicio_usuario (usuario_id),
  CONSTRAINT fk_usuarioexercicio_usuario FOREIGN KEY (usuario_id) REFERENCES usuario(id) ON DELETE CASCADE,
  CONSTRAINT fk_usuarioexercicio_exercicio FOREIGN KEY (exercicio_id) REFERENCES exercicio(id) ON DELETE CASCADE
) ENGINE=InnoDB;
