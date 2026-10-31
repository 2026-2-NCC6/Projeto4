'use strict';

require('dotenv').config();

const express = require('express');
const mysql = require('mysql2/promise');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');

const PORT = Number(process.env.PORT || 5001);
const HOST = process.env.HOST || '0.0.0.0';
const JWT_SECRET = process.env.JWT_SECRET || '';
const JWT_EXPIRES_IN = process.env.JWT_EXPIRES_IN || '7d';
const MAX_SYNC_ITEMS = 1000;

if (JWT_SECRET.length < 32 || JWT_SECRET.startsWith('troque-por')) {
  console.error('JWT_SECRET precisa ter pelo menos 32 caracteres. Copie .env.example para .env e configure-o.');
  process.exit(1);
}

const pool = mysql.createPool({
  host: process.env.DB_HOST || '127.0.0.1',
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER || 'smarttennis_app',
  password: process.env.DB_PASSWORD || '',
  database: process.env.DB_NAME || 'smarttennis_backend',
  waitForConnections: true,
  connectionLimit: Number(process.env.DB_CONNECTION_LIMIT || 10),
  queueLimit: 0,
  charset: 'utf8mb4',
  decimalNumbers: true,
  ssl: {
    rejectUnauthorized: false
  }
});

const app = express();
app.disable('x-powered-by');
app.use(helmet({ crossOriginResourcePolicy: false }));
const configuredOrigins = (process.env.CORS_ORIGIN || '*').split(',').map(v => v.trim());
app.use(cors({
  origin: (origin, callback) => {
    if (!origin || configuredOrigins.includes('*') || configuredOrigins.includes(origin)) return callback(null, true);
    return callback(new Error('Origem nao autorizada pelo CORS.'));
  }
}));
app.use(express.json({ limit: '15mb' }));
app.use(express.urlencoded({ extended: false, limit: '15mb' }));

const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 30,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'Muitas tentativas. Aguarde alguns minutos e tente novamente.', code: 'RATE_LIMIT' }
});

function asyncRoute(handler) {
  return (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);
}

function cleanEmail(value) {
  return typeof value === 'string' ? value.trim().toLowerCase() : '';
}

function publicUser(row) {
  return {
    id: Number(row.id),
    nome: row.nome,
    email: row.email,
    dataNascimento: row.data_nascimento || null
  };
}

function issueToken(row) {
  return jwt.sign({ sub: String(row.id), email: row.email }, JWT_SECRET, { expiresIn: JWT_EXPIRES_IN });
}

function requireAuth(req, res, next) {
  const header = req.get('authorization') || '';
  const match = /^Bearer\s+(.+)$/i.exec(header);
  if (!match) return res.status(401).json({ error: 'Autenticacao necessaria.', code: 'AUTH_REQUIRED' });
  try {
    const payload = jwt.verify(match[1], JWT_SECRET);
    const userId = Number(payload.sub);
    if (!Number.isInteger(userId) || userId <= 0) throw new Error('sub invalido');
    req.auth = { userId, email: payload.email };
    return next();
  } catch (_) {
    return res.status(401).json({ error: 'Sessao invalida ou expirada. Entre novamente quando estiver online.', code: 'TOKEN_INVALID' });
  }
}

function asString(value, maxLength, fallback = '') {
  if (value === null || value === undefined) return fallback;
  return String(value).trim().slice(0, maxLength);
}

function asInt(value, fallback = 0, min = 0, max = Number.MAX_SAFE_INTEGER) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.max(min, Math.min(max, Math.trunc(number)));
}

function asFloat(value, fallback = 0, min = 0, max = 1000000) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.max(min, Math.min(max, number));
}


function parseRecordes(text) {
  const values = {};
  if (typeof text !== 'string' || !text.trim()) return values;
  for (const pair of text.split(';')) {
    const index = pair.indexOf('=');
    if (index <= 0) continue;
    values[pair.slice(0, index)] = pair.slice(index + 1);
  }
  return values;
}

function mergeRecordesStrings(serverText, clientText) {
  const server = parseRecordes(serverText);
  const client = parseRecordes(clientText);
  const merged = { ...server };
  const maxNumericKeys = ['xp', 'sessoes', 'maiorCombo', 'chefoes', 'prVel', 'prForca'];
  for (const key of maxNumericKeys) {
    const values = [Number(server[key]), Number(client[key])].filter(Number.isFinite);
    if (values.length) merged[key] = String(Math.max(...values));
  }
  const forceKeys = new Set([
    ...Object.keys(server).filter(key => key.startsWith('f.')),
    ...Object.keys(client).filter(key => key.startsWith('f.'))
  ]);
  for (const key of forceKeys) {
    const fromServer = (server[key] || '').split(',').filter(Boolean);
    const fromClient = (client[key] || '').split(',').filter(Boolean);
    // Sem timestamp por swing, a lista mais longa preserva mais amostras sem duplicar
    // o mesmo lote toda vez que o celular sincroniza. O limite e o mesmo do app (30).
    const selected = fromClient.length >= fromServer.length ? fromClient : fromServer;
    if (selected.length) merged[key] = selected.slice(-30).join(',');
    else delete merged[key];
  }
  for (const [key, value] of Object.entries(client)) {
    if (!(key in merged)) merged[key] = value;
  }
  return Object.entries(merged).map(([key, value]) => `${key}=${value}`).join(';') + (Object.keys(merged).length ? ';' : '');
}

function parseGamification(value) {
  if (typeof value === 'string') {
    try { value = JSON.parse(value); } catch (_) { value = {}; }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) value = {};
  return {
    xpCarreira: asInt(value.xpCarreira, 0, 0, 2147483647),
    recordes: typeof value.recordes === 'string' ? value.recordes.slice(0, 100000) : ''
  };
}

function mergeGamification(serverValue, clientValue) {
  const server = parseGamification(serverValue);
  const client = parseGamification(clientValue);
  return {
    xpCarreira: Math.max(server.xpCarreira, client.xpCarreira),
    recordes: mergeRecordesStrings(server.recordes, client.recordes)
  };
}

function challengeFromRow(row) {
  return {
    id: row.id,
    nome: row.nome,
    descricao: row.descricao || '',
    duracaoMinutos: Number(row.duracao_minutos || 1),
    dificuldade: row.dificuldade || 'Iniciante',
    categoria: row.categoria || 'PERSONALIZADO',
    sequenciaGolpes: row.sequencia_golpes || '',
    criadoEm: Number(row.criado_em_ms || 0),
    atualizadoEm: Number(row.atualizado_em_ms || 0),
    velocidadeAlvoKmh: Number(row.velocidade_alvo_kmh || 0),
    bolasAlvo: Number(row.bolas_alvo || 0),
    usaCamera: Boolean(row.usa_camera),
    excluido: Boolean(row.excluido)
  };
}

function historyFromRow(row) {
  return {
    syncId: row.sync_id,
    treinoId: row.treino_id,
    dataConclusao: Number(row.data_conclusao_ms || 0),
    tempoSegundos: Number(row.tempo_segundos || 0),
    pontuacao: Number(row.pontuacao || 0),
    saques: Number(row.saques || 0),
    forehands: Number(row.forehands || 0),
    backhands: Number(row.backhands || 0),
    golpesCorretos: Number(row.golpes_corretos || 0),
    totalGolpes: Number(row.total_golpes || 0),
    golpesRecebidos: Number(row.golpes_recebidos || 0),
    concluido: Boolean(row.concluido),
    bolasDetectadas: Number(row.bolas_detectadas || 0),
    bolasAcimaMeta: Number(row.bolas_acima_meta || 0),
    velocidadeMaximaKmh: Number(row.velocidade_maxima_kmh || 0),
    velocidadeMediaKmh: Number(row.velocidade_media_kmh || 0),
    metricasCameraJson: row.metricas_camera_json || '[]'
  };
}

async function getSnapshot(userId) {
  const [challengeRows] = await pool.execute(
    `SELECT id, nome, descricao, duracao_minutos, dificuldade, categoria,
            sequencia_golpes, criado_em_ms, atualizado_em_ms,
            velocidade_alvo_kmh, bolas_alvo, usa_camera, excluido
       FROM desafio_personalizado WHERE usuario_id = ?
       ORDER BY atualizado_em_ms ASC, id ASC`, [userId]);
  const [historyRows] = await pool.execute(
    `SELECT sync_id, treino_id, data_conclusao_ms, tempo_segundos, pontuacao,
            saques, forehands, backhands, golpes_corretos, total_golpes,
            golpes_recebidos, concluido, bolas_detectadas, bolas_acima_meta,
            velocidade_maxima_kmh, velocidade_media_kmh, metricas_camera_json
       FROM historico_treino WHERE usuario_id = ?
       ORDER BY data_conclusao_ms ASC, id ASC`, [userId]);
  const [userRows] = await pool.execute('SELECT gamificacao_json FROM usuario WHERE id = ? LIMIT 1', [userId]);
  return {
    serverTime: Date.now(),
    challenges: challengeRows.map(challengeFromRow),
    history: historyRows.map(historyFromRow),
    gamification: parseGamification(userRows[0]?.gamificacao_json)
  };
}

app.get('/api/health', asyncRoute(async (_req, res) => {
  await pool.query('SELECT 1');
  res.json({ ok: true, service: 'smarttennis-backend', database: 'connected', time: Date.now() });
}));

app.post('/api/auth/register', authLimiter, asyncRoute(async (req, res) => {
  const nome = asString(req.body?.nome, 80);
  const email = cleanEmail(req.body?.email);
  const senha = typeof req.body?.senha === 'string' ? req.body.senha : '';
  const dataNascimento = asString(req.body?.dataNascimento, 10, '') || null;

  if (nome.length < 2) return res.status(400).json({ error: 'Informe um nome com pelo menos 2 caracteres.', code: 'INVALID_NAME' });
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) {
    return res.status(400).json({ error: 'Informe um e-mail valido.', code: 'INVALID_EMAIL' });
  }
  if (senha.length < 8 || Buffer.byteLength(senha, 'utf8') > 72) {
    return res.status(400).json({ error: 'A senha deve ter de 8 a 72 caracteres.', code: 'INVALID_PASSWORD' });
  }

  const senhaHash = await bcrypt.hash(senha, 12);
  try {
    const [result] = await pool.execute(
      'INSERT INTO usuario (nome, email, senha_hash, data_nascimento) VALUES (?, ?, ?, ?)',
      [nome, email, senhaHash, dataNascimento]);
    const user = { id: result.insertId, nome, email, data_nascimento: dataNascimento };
    return res.status(201).json({ token: issueToken(user), user: publicUser(user) });
  } catch (error) {
    if (error && error.code === 'ER_DUP_ENTRY') {
      return res.status(409).json({ error: 'Este e-mail ja possui uma conta. Entre com seu login.', code: 'EMAIL_EXISTS' });
    }
    throw error;
  }
}));

app.post('/api/auth/login', authLimiter, asyncRoute(async (req, res) => {
  const email = cleanEmail(req.body?.email);
  const senha = typeof req.body?.senha === 'string' ? req.body.senha : '';
  if (!email || !senha) return res.status(400).json({ error: 'Informe e-mail e senha.', code: 'MISSING_CREDENTIALS' });

  const [rows] = await pool.execute(
    'SELECT id, nome, email, senha_hash, data_nascimento FROM usuario WHERE email = ? LIMIT 1', [email]);
  const user = rows[0];
  if (!user || !(await bcrypt.compare(senha, user.senha_hash))) {
    return res.status(401).json({ error: 'E-mail ou senha incorretos.', code: 'INVALID_CREDENTIALS' });
  }
  return res.json({ token: issueToken(user), user: publicUser(user) });
}));

app.get('/api/auth/me', requireAuth, asyncRoute(async (req, res) => {
  const [rows] = await pool.execute(
    'SELECT id, nome, email, data_nascimento FROM usuario WHERE id = ? LIMIT 1', [req.auth.userId]);
  if (!rows[0]) return res.status(404).json({ error: 'Conta nao encontrada.', code: 'USER_NOT_FOUND' });
  res.json({ user: publicUser(rows[0]) });
}));

app.patch('/api/auth/me', requireAuth, asyncRoute(async (req, res) => {
  const nome = req.body?.nome === undefined ? undefined : asString(req.body.nome, 80);
  const dataNascimento = req.body?.dataNascimento === undefined ? undefined : (asString(req.body.dataNascimento, 10) || null);
  if (nome !== undefined && nome.length < 2) return res.status(400).json({ error: 'Nome invalido.', code: 'INVALID_NAME' });

  const updates = [];
  const values = [];
  if (nome !== undefined) { updates.push('nome = ?'); values.push(nome); }
  if (dataNascimento !== undefined) { updates.push('data_nascimento = ?'); values.push(dataNascimento); }
  if (!updates.length) return res.status(400).json({ error: 'Nenhum campo para atualizar.', code: 'EMPTY_UPDATE' });
  updates.push('atualizado_em = CURRENT_TIMESTAMP');
  values.push(req.auth.userId);
  await pool.execute(`UPDATE usuario SET ${updates.join(', ')} WHERE id = ?`, values);
  const [rows] = await pool.execute('SELECT id, nome, email, data_nascimento FROM usuario WHERE id = ?', [req.auth.userId]);
  res.json({ user: publicUser(rows[0]) });
}));

// Sincronizacao em lote: mescla os dados enviados e devolve o snapshot da conta.
// O ID do usuario nunca vem do celular; ele sempre vem do token JWT validado.
app.post('/api/sync', requireAuth, asyncRoute(async (req, res) => {
  const challenges = Array.isArray(req.body?.challenges) ? req.body.challenges : [];
  const history = Array.isArray(req.body?.history) ? req.body.history : [];
  if (challenges.length > MAX_SYNC_ITEMS || history.length > MAX_SYNC_ITEMS) {
    return res.status(413).json({ error: `Cada lista pode ter no maximo ${MAX_SYNC_ITEMS} itens.`, code: 'SYNC_TOO_LARGE' });
  }

  const connection = await pool.getConnection();
  try {
    await connection.beginTransaction();

    for (const item of challenges) {
      if (!item || typeof item !== 'object') continue;
      const id = asString(item.id, 80);
      const nome = asString(item.nome, 120);
      if (!id || !nome) continue;
      const criadoEm = asInt(item.criadoEm, Date.now());
      const atualizadoEm = asInt(item.atualizadoEm, criadoEm);
      const excluido = Boolean(item.excluido) ? 1 : 0;
      const [existingRows] = await connection.execute(
        'SELECT usuario_id, atualizado_em_ms FROM desafio_personalizado WHERE id = ? LIMIT 1', [id]);
      if (existingRows[0] && Number(existingRows[0].usuario_id) !== req.auth.userId) {
        const conflict = new Error('Conflito de identificador de desafio.');
        conflict.status = 409;
        conflict.publicCode = 'CHALLENGE_ID_CONFLICT';
        throw conflict;
      }
      if (existingRows[0] && Number(existingRows[0].atualizado_em_ms) > atualizadoEm) continue;

      await connection.execute(
        `INSERT INTO desafio_personalizado
          (id, usuario_id, nome, descricao, duracao_minutos, dificuldade, categoria,
           sequencia_golpes, criado_em_ms, atualizado_em_ms, velocidade_alvo_kmh, bolas_alvo, usa_camera, excluido)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON DUPLICATE KEY UPDATE
          nome = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(nome), nome),
          descricao = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(descricao), descricao),
          duracao_minutos = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(duracao_minutos), duracao_minutos),
          dificuldade = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(dificuldade), dificuldade),
          categoria = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(categoria), categoria),
          sequencia_golpes = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(sequencia_golpes), sequencia_golpes),
          criado_em_ms = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(criado_em_ms), criado_em_ms),
          velocidade_alvo_kmh = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(velocidade_alvo_kmh), velocidade_alvo_kmh),
          bolas_alvo = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(bolas_alvo), bolas_alvo),
          usa_camera = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(usa_camera), usa_camera),
          excluido = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(excluido), excluido),
          atualizado_em_ms = IF(usuario_id = VALUES(usuario_id) AND VALUES(atualizado_em_ms) >= atualizado_em_ms, VALUES(atualizado_em_ms), atualizado_em_ms)`,
        [id, req.auth.userId, nome, asString(item.descricao, 4000), asInt(item.duracaoMinutos, 1, 1, 1440),
          asString(item.dificuldade, 40, 'Iniciante'), asString(item.categoria, 60, 'PERSONALIZADO'),
          asString(item.sequenciaGolpes, 1000), criadoEm, atualizadoEm,
          asFloat(item.velocidadeAlvoKmh), asInt(item.bolasAlvo, 0, 0, 100000), item.usaCamera ? 1 : 0, excluido]);
    }

    for (const item of history) {
      if (!item || typeof item !== 'object') continue;
      const syncId = asString(item.syncId, 36);
      const treinoId = asString(item.treinoId, 120);
      if (!syncId || !treinoId) continue;
      // INSERT IGNORE + chave unica tornam duas sincronizacoes simultaneas idempotentes.
      const metricas = item.metricasCameraJson == null ? '[]' : String(item.metricasCameraJson).slice(0, 200000);
      await connection.execute(
        `INSERT IGNORE INTO historico_treino
          (usuario_id, sync_id, treino_id, data_conclusao_ms, tempo_segundos, pontuacao,
           saques, forehands, backhands, golpes_corretos, total_golpes, golpes_recebidos,
           concluido, bolas_detectadas, bolas_acima_meta, velocidade_maxima_kmh,
           velocidade_media_kmh, metricas_camera_json)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [req.auth.userId, syncId, treinoId, asInt(item.dataConclusao, Date.now()),
          asInt(item.tempoSegundos), asInt(item.pontuacao, 0, -1000000, 1000000), asInt(item.saques),
          asInt(item.forehands), asInt(item.backhands), asInt(item.golpesCorretos), asInt(item.totalGolpes),
          asInt(item.golpesRecebidos), item.concluido ? 1 : 0, asInt(item.bolasDetectadas),
          asInt(item.bolasAcimaMeta), asFloat(item.velocidadeMaximaKmh), asFloat(item.velocidadeMediaKmh), metricas]);
    }

    if (req.body?.gamification && typeof req.body.gamification === 'object') {
      const [userRows] = await connection.execute(
        'SELECT gamificacao_json FROM usuario WHERE id = ? FOR UPDATE', [req.auth.userId]);
      const mergedGamification = mergeGamification(userRows[0]?.gamificacao_json, req.body.gamification);
      await connection.execute('UPDATE usuario SET gamificacao_json = ? WHERE id = ?',
        [JSON.stringify(mergedGamification), req.auth.userId]);
    }

    await connection.commit();
  } catch (error) {
    await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }

  res.json(await getSnapshot(req.auth.userId));
}));

app.get('/api/sync', requireAuth, asyncRoute(async (req, res) => {
  res.json(await getSnapshot(req.auth.userId));
}));

app.use((req, res) => res.status(404).json({ error: 'Rota nao encontrada.', code: 'NOT_FOUND' }));
app.use((error, _req, res, _next) => {
  if (res.headersSent) return;
  if (error && error.status) {
    return res.status(error.status).json({ error: error.message || 'Falha na requisicao.', code: error.publicCode || 'REQUEST_FAILED' });
  }
  if (error && error.type === 'entity.too.large') {
    return res.status(413).json({ error: 'Corpo da requisicao muito grande.', code: 'PAYLOAD_TOO_LARGE' });
  }
  console.error('[API]', error && error.stack ? error.stack : error);
  res.status(500).json({ error: 'Erro interno no servidor.', code: 'INTERNAL_ERROR' });
});

async function start() {
  try {
    await pool.query('SELECT 1');
    app.listen(PORT, HOST, () => {
      console.log(`SmartTennis API ouvindo em http://${HOST}:${PORT}`);
      console.log(`Health check: http://127.0.0.1:${PORT}/api/health`);
    });
  } catch (error) {
    console.error('Nao foi possivel conectar ao MySQL. Confira o .env e execute sql/schema.sql.');
    console.error(error.message);
    process.exit(1);
  }
}

start();
