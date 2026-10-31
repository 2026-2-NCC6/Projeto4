-- Usuario somente leitura para o dashboard. Execute como administrador do MySQL.
-- Troque a senha e restrinja o host ('%' -> IP/host do dashboard) sempre que possivel.
CREATE USER IF NOT EXISTS 'smarttennis_dashboard'@'%' IDENTIFIED BY 'TROQUE-ESTA-SENHA';

-- Na tabela usuario liberamos so as colunas necessarias: senha_hash fica inacessivel.
GRANT SELECT (id, nome, email, data_nascimento, gamificacao_json, criado_em, atualizado_em)
  ON smarttennis_backend.usuario TO 'smarttennis_dashboard'@'%';
GRANT SELECT ON smarttennis_backend.historico_treino      TO 'smarttennis_dashboard'@'%';
GRANT SELECT ON smarttennis_backend.desafio_personalizado TO 'smarttennis_dashboard'@'%';
FLUSH PRIVILEGES;
