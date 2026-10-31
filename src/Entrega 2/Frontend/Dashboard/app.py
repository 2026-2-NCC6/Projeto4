# -*- coding: utf-8 -*-
"""
Smart Ace / SmartTennis - Dashboard de monitoramento e analise de uso do app.

Fontes de dados (escolhidas na barra lateral):
  1. Banco de dados MySQL  -> visao GLOBAL (todos os usuarios). Use um usuario somente leitura.
  2. API do backend        -> uma conta por vez (login + GET /api/sync); nao precisa de acesso ao banco.
  3. Demonstracao          -> dados ficticios, para testar/apresentar sem backend.

Rodar:  streamlit run app.py
"""
from __future__ import annotations

import hmac
import json
import os
import ssl
import time

import numpy as np
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
import requests
import streamlit as st
from dotenv import load_dotenv
from sqlalchemy import create_engine, text
from sqlalchemy.engine import URL

load_dotenv()

TZ = "America/Sao_Paulo"
DOW = ["Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"]
DIA = pd.Timedelta(days=1)
MAX_METRICAS_CHARS = 200_000  # limite aplicado pelo server.js ao metricasCameraJson

FONTE_DB = "Banco de dados (visão global)"
FONTE_API = "API do backend (uma conta)"
FONTE_DEMO = "Demonstração (dados fictícios)"


def cfg(name: str, default: str = "") -> str:
    """st.secrets > variavel de ambiente/.env > padrao."""
    try:
        if name in st.secrets:
            return str(st.secrets[name])
    except Exception:
        pass
    return os.getenv(name, default)


# --------------------------------------------------------------------------------------
# Acesso a dados
# --------------------------------------------------------------------------------------
# Nunca selecionamos senha_hash. metricas_camera_json (ate 200 KB por linha) so e lido sob demanda.
Q_USUARIOS = """SELECT id, nome, email, data_nascimento, gamificacao_json, criado_em, atualizado_em
                  FROM usuario"""
Q_HISTORICO = """SELECT id, usuario_id, sync_id, treino_id, data_conclusao_ms, tempo_segundos, pontuacao,
                        saques, forehands, backhands, golpes_corretos, total_golpes, golpes_recebidos,
                        concluido, bolas_detectadas, bolas_acima_meta, velocidade_maxima_kmh,
                        velocidade_media_kmh, CHAR_LENGTH(metricas_camera_json) AS metricas_chars,
                        recebido_em
                   FROM historico_treino"""
Q_DESAFIOS = """SELECT id, usuario_id, nome, descricao, duracao_minutos, dificuldade, categoria,
                       sequencia_golpes, criado_em_ms, atualizado_em_ms, velocidade_alvo_kmh,
                       bolas_alvo, excluido
                  FROM desafio_personalizado"""

HIST_COLS = ["id", "usuario_id", "sync_id", "treino_id", "data_conclusao_ms", "tempo_segundos",
             "pontuacao", "saques", "forehands", "backhands", "golpes_corretos", "total_golpes",
             "golpes_recebidos", "concluido", "bolas_detectadas", "bolas_acima_meta",
             "velocidade_maxima_kmh", "velocidade_media_kmh", "metricas_chars", "recebido_em"]
DES_COLS = ["id", "usuario_id", "nome", "descricao", "duracao_minutos", "dificuldade", "categoria",
            "sequencia_golpes", "criado_em_ms", "atualizado_em_ms", "velocidade_alvo_kmh",
            "bolas_alvo", "excluido"]
USER_COLS = ["id", "nome", "email", "data_nascimento", "gamificacao_json", "criado_em", "atualizado_em"]

API_HIST_MAP = {"syncId": "sync_id", "treinoId": "treino_id", "dataConclusao": "data_conclusao_ms",
                "tempoSegundos": "tempo_segundos", "pontuacao": "pontuacao", "saques": "saques",
                "forehands": "forehands", "backhands": "backhands", "golpesCorretos": "golpes_corretos",
                "totalGolpes": "total_golpes", "golpesRecebidos": "golpes_recebidos",
                "concluido": "concluido", "bolasDetectadas": "bolas_detectadas",
                "bolasAcimaMeta": "bolas_acima_meta", "velocidadeMaximaKmh": "velocidade_maxima_kmh",
                "velocidadeMediaKmh": "velocidade_media_kmh", "metricasCameraJson": "metricas_camera_json"}
API_DES_MAP = {"id": "id", "nome": "nome", "descricao": "descricao", "duracaoMinutos": "duracao_minutos",
               "dificuldade": "dificuldade", "categoria": "categoria", "sequenciaGolpes": "sequencia_golpes",
               "criadoEm": "criado_em_ms", "atualizadoEm": "atualizado_em_ms",
               "velocidadeAlvoKmh": "velocidade_alvo_kmh", "bolasAlvo": "bolas_alvo", "excluido": "excluido"}


@st.cache_resource(show_spinner=False)
def get_engine(host, port, user, password, database, use_ssl):
    url = URL.create("mysql+pymysql", username=user, password=password, host=host,
                     port=int(port), database=database, query={"charset": "utf8mb4"})
    connect_args = {"connect_timeout": 10}
    if use_ssl:  # o server.js usa ssl com rejectUnauthorized:false
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        connect_args["ssl"] = ctx
    return create_engine(url, pool_pre_ping=True, pool_recycle=1800, connect_args=connect_args)


def ts_local(series: pd.Series, tz_src: str) -> pd.Series:
    """TIMESTAMP do MySQL (naive) -> horario de Sao Paulo, naive."""
    s = pd.to_datetime(series, errors="coerce")
    if s.dt.tz is None:
        s = s.dt.tz_localize(tz_src, ambiguous="NaT", nonexistent="NaT")
    return s.dt.tz_convert(TZ).dt.tz_localize(None)


def ms_local(series: pd.Series) -> pd.Series:
    s = pd.to_datetime(pd.to_numeric(series, errors="coerce"), unit="ms", utc=True, errors="coerce")
    return s.dt.tz_convert(TZ).dt.tz_localize(None)


def parse_data_nasc(series: pd.Series) -> pd.Series:
    s = series.astype("string")
    a = pd.to_datetime(s, format="%d/%m/%Y", errors="coerce")
    b = pd.to_datetime(s, format="%Y-%m-%d", errors="coerce")
    return a.fillna(b)


def parse_gamif(raw) -> dict:
    """gamificacao_json = {"xpCarreira": int, "recordes": "chave=valor;chave=valor;"} (ver server.js)."""
    out = {"xp": np.nan, "sessoes": np.nan, "maior_combo": np.nan,
           "chefoes": np.nan, "pr_vel": np.nan, "pr_forca": np.nan}
    try:
        v = json.loads(raw) if isinstance(raw, str) else (raw or {})
    except Exception:
        return out
    if not isinstance(v, dict):
        return out
    rec = {}
    for pair in str(v.get("recordes", "")).split(";"):
        k, _, val = pair.partition("=")
        if k and val:
            rec[k] = val

    def num(x):
        try:
            return float(x)
        except (TypeError, ValueError):
            return np.nan

    xps = [x for x in (num(v.get("xpCarreira")), num(rec.get("xp"))) if not np.isnan(x)]
    out["xp"] = max(xps) if xps else np.nan
    for dst, key in (("sessoes", "sessoes"), ("maior_combo", "maiorCombo"), ("chefoes", "chefoes"),
                     ("pr_vel", "prVel"), ("pr_forca", "prForca")):
        out[dst] = num(rec.get(key))
    return out


def prep_usuarios(u: pd.DataFrame, tz_db: str) -> pd.DataFrame:
    u = u.reindex(columns=USER_COLS).copy()
    u["criado"] = ts_local(u["criado_em"], tz_db)
    nasc = parse_data_nasc(u["data_nascimento"])
    agora = pd.Timestamp.now(tz=TZ).tz_localize(None)
    idade = (agora - nasc).dt.days / 365.25
    u["idade"] = idade.where((idade >= 5) & (idade <= 100))
    g = pd.DataFrame(u["gamificacao_json"].map(parse_gamif).tolist(), index=u.index)
    return pd.concat([u, g], axis=1)


def prep_historico(h: pd.DataFrame, tz_db: str) -> pd.DataFrame:
    extra = [c for c in h.columns if c not in HIST_COLS]
    h = h.reindex(columns=HIST_COLS + extra).copy()
    for c in ["tempo_segundos", "pontuacao", "saques", "forehands", "backhands", "golpes_corretos",
              "total_golpes", "golpes_recebidos", "bolas_detectadas", "bolas_acima_meta",
              "velocidade_maxima_kmh", "velocidade_media_kmh", "metricas_chars"]:
        h[c] = pd.to_numeric(h[c], errors="coerce").fillna(0)
    h["concluido"] = h["concluido"].fillna(0).astype(bool)
    h["data"] = ms_local(h["data_conclusao_ms"])
    h = h[h["data"].notna()].copy()
    h["dia"] = h["data"].dt.normalize()
    h["hora"] = h["data"].dt.hour
    h["dow"] = h["data"].dt.dayofweek
    h["semana"] = h["data"].dt.to_period("W-SUN").dt.start_time
    h["minutos"] = h["tempo_segundos"] / 60
    h["precisao"] = (h["golpes_corretos"] / h["total_golpes"].where(h["total_golpes"] > 0) * 100).clip(0, 100)
    h["vel_max"] = h["velocidade_maxima_kmh"].where(h["velocidade_maxima_kmh"] > 0)
    h["vel_media"] = h["velocidade_media_kmh"].where(h["velocidade_media_kmh"] > 0)
    h["recebido"] = ts_local(h["recebido_em"], tz_db)
    h["atraso_sync_h"] = (h["recebido"] - h["data"]).dt.total_seconds() / 3600
    return h.reset_index(drop=True)


def prep_desafios(d: pd.DataFrame) -> pd.DataFrame:
    d = d.reindex(columns=DES_COLS).copy()
    d["excluido"] = d["excluido"].fillna(0).astype(bool)
    d["criado"] = ms_local(d["criado_em_ms"])
    d["atualizado"] = ms_local(d["atualizado_em_ms"])
    d["duracao_minutos"] = pd.to_numeric(d["duracao_minutos"], errors="coerce")
    d["velocidade_alvo_kmh"] = pd.to_numeric(d["velocidade_alvo_kmh"], errors="coerce").fillna(0)
    d["bolas_alvo"] = pd.to_numeric(d["bolas_alvo"], errors="coerce").fillna(0)
    return d


@st.cache_data(ttl=60, show_spinner="Consultando o banco de dados…")
def load_db(cfg_t: tuple, tz_db: str) -> dict:
    eng = get_engine(*cfg_t)
    with eng.connect() as c:
        u = pd.read_sql(text(Q_USUARIOS), c)
        h = pd.read_sql(text(Q_HISTORICO), c)
        d = pd.read_sql(text(Q_DESAFIOS), c)
    return {"u": prep_usuarios(u, tz_db), "h": prep_historico(h, tz_db), "d": prep_desafios(d),
            "loaded_at": time.time()}


@st.cache_data(ttl=60, show_spinner=False)
def db_counts(cfg_t: tuple) -> dict:
    with get_engine(*cfg_t).connect() as c:
        return {t: int(c.execute(text(f"SELECT COUNT(*) FROM {t}")).scalar())
                for t in ("usuario", "desafio_personalizado", "historico_treino")}


def fetch_camera_json(modo: str, cfg_t, hist_id, inline):
    """No modo banco busca o JSON sob demanda; nos demais ele ja esta no DataFrame."""
    if modo != "db":
        return inline
    with get_engine(*cfg_t).connect() as c:
        return c.execute(text("SELECT metricas_camera_json FROM historico_treino WHERE id = :i"),
                         {"i": int(hist_id)}).scalar()


# ---- API --------------------------------------------------------------------------------
def api_login(base: str, email: str, senha: str):
    r = requests.post(f"{base.rstrip('/')}/api/auth/login", json={"email": email, "senha": senha}, timeout=15)
    if not r.ok:
        try:
            msg = r.json().get("error", r.text)
        except Exception:
            msg = r.text
        raise RuntimeError(f"{r.status_code}: {msg}")
    data = r.json()
    return data["token"], data["user"]


@st.cache_data(ttl=30, show_spinner="Sincronizando com a API…")
def load_api(base: str, token: str, user: dict, tz_db: str) -> dict:
    r = requests.get(f"{base.rstrip('/')}/api/sync", headers={"Authorization": f"Bearer {token}"}, timeout=30)
    if r.status_code == 401:
        raise PermissionError("Sessão expirada. Entre novamente.")
    r.raise_for_status()
    snap = r.json()
    uid = int(user["id"])
    h = pd.DataFrame(snap.get("history", [])).rename(columns=API_HIST_MAP)
    h = h.reindex(columns=[c for c in API_HIST_MAP.values()])
    h["id"] = range(1, len(h) + 1)
    h["usuario_id"] = uid
    h["metricas_chars"] = h["metricas_camera_json"].astype("string").str.len()
    d = pd.DataFrame(snap.get("challenges", [])).rename(columns=API_DES_MAP)
    d = d.reindex(columns=list(API_DES_MAP.values()))
    d["usuario_id"] = uid
    u = pd.DataFrame([{"id": uid, "nome": user.get("nome"), "email": user.get("email"),
                       "data_nascimento": user.get("dataNascimento"),
                       "gamificacao_json": json.dumps(snap.get("gamification", {}))}])
    return {"u": prep_usuarios(u, tz_db), "h": prep_historico(h, tz_db), "d": prep_desafios(d),
            "loaded_at": time.time()}


@st.cache_data(ttl=10, show_spinner=False)
def api_health(base: str) -> dict:
    t0 = time.perf_counter()
    try:
        r = requests.get(f"{base.rstrip('/')}/api/health", timeout=8)
        ms = (time.perf_counter() - t0) * 1000
        data = r.json() if "json" in r.headers.get("content-type", "") else {}
        return {"ok": bool(r.ok and data.get("ok")), "status": r.status_code, "ms": ms, "data": data}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "status": None, "ms": None, "data": {}, "error": str(e)}


# ---- Demo -------------------------------------------------------------------------------
@st.cache_data(show_spinner=False)
def load_demo(seed: int = 7) -> dict:
    rng = np.random.default_rng(seed)
    agora = pd.Timestamp.now(tz=TZ).tz_localize(None)
    n = 45
    criado = agora - pd.to_timedelta(rng.integers(2, 150, n), unit="D") - pd.to_timedelta(rng.integers(0, 86400, n), unit="s")
    nivel = rng.uniform(0.45, 0.8, n)
    engaj = rng.gamma(1.2, 6, n)
    builtin = ["saque-basico", "forehand-cross", "backhand-linha", "rally-continuo", "velocidade-max"]
    cats = ["SAQUE", "FOREHAND", "BACKHAND", "VELOCIDADE", "PERSONALIZADO"]
    difs = ["Iniciante", "Intermediário", "Avançado"]

    des = []
    for i in range(18):
        uid = int(rng.integers(1, n + 1))
        c = agora - pd.to_timedelta(rng.integers(1, 100), unit="D")
        des.append({"id": f"demo-desafio-{i:02d}", "usuario_id": uid, "nome": f"Desafio {i + 1}",
                    "descricao": "Desafio fictício", "duracao_minutos": int(rng.integers(3, 25)),
                    "dificuldade": rng.choice(difs), "categoria": rng.choice(cats),
                    "sequencia_golpes": "F,B,S", "criado_em_ms": int(c.tz_localize(TZ).timestamp() * 1000),
                    "atualizado_em_ms": int(c.tz_localize(TZ).timestamp() * 1000),
                    "velocidade_alvo_kmh": float(rng.choice([0, 80, 100, 120])),
                    "bolas_alvo": int(rng.choice([0, 20, 40])), "excluido": int(rng.random() < 0.12)})
    custom_ids = [x["id"] for x in des]

    hist, k = [], 0
    for i in range(n):
        n_sess = int(rng.poisson(engaj[i]))
        for _ in range(n_sess):
            dias = min(rng.exponential(30), max((agora - criado[i]).days, 0))
            hora = int(rng.choice([7, 8, 9, 10, 17, 18, 19, 20, 21], p=[.08, .1, .08, .06, .1, .18, .2, .12, .08]))
            dt = (criado[i] + pd.Timedelta(days=float(dias))).normalize() + pd.Timedelta(hours=hora, minutes=int(rng.integers(0, 60)))
            if dt > agora:
                dt = agora - pd.Timedelta(hours=1)
            total = int(rng.integers(20, 200))
            corretos = int(rng.binomial(total, min(nivel[i] + dias / 400, 0.95)))
            sq = int(total * rng.uniform(0.1, 0.3)); fh = int((total - sq) * rng.uniform(0.4, 0.6)); bh = total - sq - fh
            bolas = int(rng.integers(10, 80))
            vmax = float(max(0, rng.normal(95, 18))) if rng.random() > 0.15 else 0.0
            cam = json.dumps([{"bola": b + 1, "velocidadeKmh": round(float(rng.normal(vmax * 0.85 + 1, 10)), 1),
                               "acimaMeta": bool(rng.random() < 0.4)} for b in range(min(bolas, 25))])
            lag = float(rng.exponential(4)) if rng.random() < 0.3 else float(rng.uniform(0.01, 0.2))
            k += 1
            hist.append({"id": k, "usuario_id": i + 1, "sync_id": f"demo-{k}",
                         "treino_id": str(rng.choice(builtin + custom_ids, p=None)),
                         "data_conclusao_ms": int(dt.tz_localize(TZ).timestamp() * 1000),
                         "tempo_segundos": int(rng.integers(300, 2400)), "pontuacao": int(corretos * rng.uniform(8, 14)),
                         "saques": sq, "forehands": fh, "backhands": bh, "golpes_corretos": corretos,
                         "total_golpes": total, "golpes_recebidos": int(rng.integers(0, 40)),
                         "concluido": int(rng.random() < 0.8), "bolas_detectadas": bolas,
                         "bolas_acima_meta": int(rng.binomial(bolas, 0.4)),
                         "velocidade_maxima_kmh": vmax, "velocidade_media_kmh": vmax * 0.82,
                         "metricas_camera_json": cam, "metricas_chars": len(cam),
                         "recebido_em": dt + pd.Timedelta(hours=lag)})
    h = pd.DataFrame(hist)
    xp = h.groupby("usuario_id").pontuacao.sum().reindex(range(1, n + 1), fill_value=0)
    u = pd.DataFrame({"id": range(1, n + 1), "nome": [f"Jogador {i:02d}" for i in range(1, n + 1)],
                      "email": [f"jogador{i:02d}@exemplo.com" for i in range(1, n + 1)],
                      "data_nascimento": [f"{int(rng.integers(1, 28)):02d}/{int(rng.integers(1, 12)):02d}/{int(rng.integers(1965, 2012))}" for _ in range(n)],
                      "gamificacao_json": [json.dumps({"xpCarreira": int(x), "recordes": f"xp={int(x)};sessoes={int(rng.integers(1, 60))};maiorCombo={int(rng.integers(3, 40))};chefoes={int(rng.integers(0, 6))};prVel={int(rng.integers(70, 140))};prForca={int(rng.integers(20, 90))};"}) for x in xp],
                      "criado_em": criado, "atualizado_em": criado})
    return {"u": prep_usuarios(u, TZ), "h": prep_historico(h, TZ), "d": prep_desafios(pd.DataFrame(des)),
            "loaded_at": time.time()}


# --------------------------------------------------------------------------------------
# Metricas (funcoes puras, faceis de testar)
# --------------------------------------------------------------------------------------
def fmt(x, nd=0, suf=""):
    if x is None or (isinstance(x, (float, np.floating)) and np.isnan(x)):
        return "—"
    s = f"{x:,.{nd}f}".replace(",", "X").replace(".", ",").replace("X", ".")
    return s + suf


def delta_pct(cur, prev):
    try:
        if prev is None or np.isnan(prev) or prev == 0 or np.isnan(cur):
            return None
        return f"{(cur - prev) / prev * 100:+.0f}%"
    except TypeError:
        return None


def mask_email(e):
    if not isinstance(e, str) or "@" not in e:
        return e
    loc, dom = e.split("@", 1)
    return loc[:2] + "•••@" + dom


def filtrar_periodo(h: pd.DataFrame, ini, fim) -> pd.DataFrame:
    return h[(h["data"] >= ini) & (h["data"] < fim + DIA)]


def resumo_periodo(hp: pd.DataFrame) -> dict:
    n = len(hp)
    return {"treinos": n, "ativos": hp["usuario_id"].nunique(), "horas": hp["minutos"].sum() / 60,
            "conclusao": hp["concluido"].mean() * 100 if n else np.nan,
            "precisao": hp["precisao"].mean() if n else np.nan,
            "golpes": int(hp["total_golpes"].sum()), "vel_max": hp["vel_max"].max() if n else np.nan,
            "bolas": int(hp["bolas_detectadas"].sum()),
            "pontuacao": hp["pontuacao"].mean() if n else np.nan}


def ativos_janela(h: pd.DataFrame, fim, dias: int) -> int:
    ini = fim - pd.Timedelta(days=dias - 1)
    return h.loc[(h["data"] >= ini) & (h["data"] < fim + DIA), "usuario_id"].nunique()


def cohort_retention(u: pd.DataFrame, h: pd.DataFrame, hoje, max_weeks: int = 8) -> pd.DataFrame:
    """% de usuarios de cada coorte (semana de cadastro) com >=1 treino na semana k apos o cadastro."""
    uu = u.loc[u["criado"].notna(), ["id", "criado"]].copy()
    if uu.empty or h.empty:
        return pd.DataFrame()
    uu["coorte"] = uu["criado"].dt.to_period("W-SUN").dt.start_time
    m = h[["usuario_id", "data"]].merge(uu, left_on="usuario_id", right_on="id")
    # treinos feitos offline antes do cadastro contam na semana 0
    m["sem"] = ((m["data"].dt.normalize() - m["criado"].dt.normalize()).dt.days // 7).clip(lower=0)
    m = m[m["sem"] <= max_weeks]
    ativos = m.groupby(["coorte", "sem"])["usuario_id"].nunique().unstack(fill_value=0)
    tam = uu.groupby("coorte")["id"].nunique()
    ativos = ativos.reindex(index=tam.index, columns=range(max_weeks + 1), fill_value=0)
    ret = ativos.div(tam, axis=0) * 100
    idade_sem = ((hoje - ret.index.to_series()).dt.days // 7).to_numpy()
    for k in ret.columns:
        ret.loc[idade_sem < k, k] = np.nan  # coorte ainda nao tem idade suficiente
    ret.index = [f"{c:%d/%m/%y} (n={int(tam[c])})" for c in ret.index]
    return ret


def resumo_usuarios(u: pd.DataFrame, h: pd.DataFrame, hoje) -> pd.DataFrame:
    g = h.groupby("usuario_id").agg(treinos=("sync_id", "count"), minutos=("minutos", "sum"),
                                    precisao=("precisao", "mean"), ultima=("data", "max"),
                                    primeira=("data", "min"), concluidos=("concluido", "sum"))
    r = u.merge(g, left_on="id", right_index=True, how="left")
    r["treinos"] = r["treinos"].fillna(0).astype(int)
    dias = (hoje - r["ultima"].dt.normalize()).dt.days
    r["dias_sem_treinar"] = dias
    r["status"] = np.select([r["treinos"] == 0, dias <= 7, dias <= 30],
                            ["Nunca treinou", "Ativo (≤7d)", "Em risco (8–30d)"], "Inativo (>30d)")
    return r


def camera_df(raw):
    """Estrutura de metricas_camera_json nao e fixa: tenta transformar em tabela."""
    try:
        obj = json.loads(raw) if isinstance(raw, str) else raw
    except Exception:
        return None, "JSON inválido ou truncado (o servidor corta em 200.000 caracteres)."
    if isinstance(obj, dict):
        for v in obj.values():
            if isinstance(v, list) and v and isinstance(v[0], dict):
                obj = v
                break
        else:
            obj = [obj]
    if isinstance(obj, list) and obj and all(isinstance(x, dict) for x in obj):
        return pd.json_normalize(obj), None
    if isinstance(obj, list) and obj:
        return pd.DataFrame({"valor": obj}), None
    return None, "Sem métricas de câmera nesta sessão."


def qualidade(h: pd.DataFrame, agora) -> pd.DataFrame:
    checks = {
        "Sessões com total de golpes = 0": (h["total_golpes"] == 0).sum(),
        "Golpes corretos > total de golpes": (h["golpes_corretos"] > h["total_golpes"]).sum(),
        "Tempo de sessão = 0 s": (h["tempo_segundos"] == 0).sum(),
        "Bolas acima da meta > bolas detectadas": (h["bolas_acima_meta"] > h["bolas_detectadas"]).sum(),
        "Data de conclusão no futuro": (h["data"] > agora + pd.Timedelta(hours=1)).sum(),
        f"Métricas de câmera truncadas (≥ {MAX_METRICAS_CHARS:,} caracteres)".replace(",", "."):
            (h["metricas_chars"] >= MAX_METRICAS_CHARS).sum(),
    }
    out = pd.DataFrame({"verificação": list(checks), "ocorrências": [int(v) for v in checks.values()]})
    out["% das sessões"] = (out["ocorrências"] / max(len(h), 1) * 100).round(1)
    return out


# --------------------------------------------------------------------------------------
# UI helpers
# --------------------------------------------------------------------------------------
def plot(fig, height=340):
    fig.update_layout(margin=dict(l=10, r=10, t=50, b=10), height=height, legend_title_text="")
    st.plotly_chart(fig)


def kpi(col, label, valor, delta=None, help=None):
    col.metric(label, valor, delta=delta, help=help, border=True)


def gate():
    pw = cfg("DASHBOARD_PASSWORD")
    if not pw or st.session_state.get("auth_ok"):
        return
    st.title("🎾 Smart Ace — Dashboard")
    typed = st.text_input("Senha de acesso", type="password")
    if typed:
        if hmac.compare_digest(typed, pw):
            st.session_state["auth_ok"] = True
            st.rerun()
        st.error("Senha incorreta.")
    st.stop()


# --------------------------------------------------------------------------------------
# Abas
# --------------------------------------------------------------------------------------
def aba_visao_geral(h, hp, hprev, u, d, ini, fim):
    cur = resumo_periodo(hp)
    ant = resumo_periodo(hprev) if hprev is not None else None
    tem_criado = u["criado"].notna().any()
    novos = int(((u["criado"] >= ini) & (u["criado"] < fim + DIA)).sum()) if tem_criado else None

    def dl(k):
        return delta_pct(cur[k], ant[k]) if ant else None

    c = st.columns(5)
    kpi(c[0], "Usuários cadastrados", fmt(len(u)),
        f"{novos:+d} no período" if novos is not None else None)
    kpi(c[1], "Usuários ativos", fmt(cur["ativos"]), dl("ativos"), "Usuários com ≥1 treino concluído/sincronizado no período.")
    kpi(c[2], "Treinos", fmt(cur["treinos"]), dl("treinos"))
    kpi(c[3], "Horas treinadas", fmt(cur["horas"], 1), dl("horas"))
    kpi(c[4], "Taxa de conclusão", fmt(cur["conclusao"], 1, "%"), dl("conclusao"),
        "Sessões marcadas como concluídas ÷ total de sessões.")
    c = st.columns(5)
    kpi(c[0], "Precisão média", fmt(cur["precisao"], 1, "%"), dl("precisao"), "golpes_corretos ÷ total_golpes.")
    kpi(c[1], "Golpes", fmt(cur["golpes"]), dl("golpes"))
    kpi(c[2], "Velocidade máx.", fmt(cur["vel_max"], 0, " km/h"))
    kpi(c[3], "Bolas detectadas", fmt(cur["bolas"]), dl("bolas"))
    kpi(c[4], "Pontuação média", fmt(cur["pontuacao"], 0), dl("pontuacao"))

    if hp.empty:
        st.info("Nenhum treino no período selecionado.")
        return

    idx = pd.date_range(ini, fim, freq="D", name="dia")
    daily = hp.groupby("dia").agg(treinos=("sync_id", "count"), ativos=("usuario_id", "nunique")).reindex(idx, fill_value=0)
    c1, c2 = st.columns(2)
    with c1:
        plot(px.bar(daily.reset_index(), x="dia", y="treinos", title="Treinos por dia"))
    with c2:
        plot(px.line(daily.reset_index(), x="dia", y="ativos", markers=True, title="Usuários ativos por dia"))

    c1, c2 = st.columns(2)
    with c1:
        if tem_criado:
            cad = u.loc[(u["criado"] >= ini) & (u["criado"] < fim + DIA)].groupby(u["criado"].dt.normalize()).size()
            cad = cad.reindex(idx, fill_value=0).rename("novos").reset_index()
            cad["acumulado no período"] = cad["novos"].cumsum()
            fig = px.bar(cad, x="dia", y="novos", title="Novos cadastros por dia")
            fig.add_trace(go.Scatter(x=cad["dia"], y=cad["acumulado no período"], name="acumulado", yaxis="y2"))
            fig.update_layout(yaxis2=dict(overlaying="y", side="right", showgrid=False))
            plot(fig)
        else:
            st.caption("Data de cadastro indisponível nesta fonte.")
    with c2:
        tipos = pd.DataFrame({"golpe": ["Saque", "Forehand", "Backhand"],
                              "total": [hp["saques"].sum(), hp["forehands"].sum(), hp["backhands"].sum()]})
        plot(px.pie(tipos, names="golpe", values="total", hole=0.5, title="Mix de golpes"))

    piv = (hp.pivot_table(index="dow", columns="hora", values="sync_id", aggfunc="count")
           .reindex(index=range(7), columns=range(24)).fillna(0))
    piv.index = DOW
    plot(px.imshow(piv, aspect="auto", color_continuous_scale="Greens", title="Quando as pessoas treinam (dia × hora)",
                   labels=dict(x="Hora do dia", y="", color="Treinos")), 300)


def aba_engajamento(h, hp, u, d, fim, hoje):
    dau, wau, mau = (ativos_janela(h, fim, n) for n in (1, 7, 30))
    dias30 = pd.date_range(fim - 29 * DIA, fim)
    media_dau = h.groupby("dia")["usuario_id"].nunique().reindex(dias30, fill_value=0).mean() if not h.empty else np.nan
    stick = media_dau / mau * 100 if mau else np.nan
    c = st.columns(5)
    kpi(c[0], "DAU (no último dia)", fmt(dau))
    kpi(c[1], "WAU (7 dias)", fmt(wau))
    kpi(c[2], "MAU (30 dias)", fmt(mau))
    kpi(c[3], "Stickiness", fmt(stick, 1, "%"), help="DAU médio dos últimos 30 dias ÷ MAU. Quanto maior, mais frequente o uso.")
    tem_criado = u["criado"].notna().any()
    if tem_criado and not h.empty:
        prim = h.groupby("usuario_id")["data"].min().rename("primeiro")
        t = u.merge(prim, left_on="id", right_index=True)
        horas = ((t["primeiro"] - t["criado"]).dt.total_seconds() / 3600).clip(lower=0)
        kpi(c[4], "Mediana até 1º treino", fmt(horas.median(), 1, " h"), help="Do cadastro ao primeiro treino registrado.")
    else:
        kpi(c[4], "Mediana até 1º treino", "—")

    c1, c2 = st.columns(2)
    with c1:
        st.subheader("Funil de ativação")
        com_treino = len(set(h["usuario_id"]) & set(u["id"]))
        tres = int((h.groupby("usuario_id").size() >= 3).sum())
        conc = h.loc[h["concluido"], "usuario_id"].nunique()
        criou = d.loc[~d["excluido"], "usuario_id"].nunique()
        etapas = ["Cadastrados", "≥1 treino", "≥3 treinos", "≥1 treino concluído", "Criou desafio próprio"]
        fig = go.Figure(go.Funnel(y=etapas, x=[len(u), com_treino, tres, conc, criou], textinfo="value+percent initial"))
        plot(fig, 360)
    with c2:
        st.subheader("Treinos por usuário (período)")
        if hp.empty:
            st.info("Sem treinos no período.")
        else:
            por_user = hp.groupby("usuario_id").size().rename("treinos").reset_index()
            plot(px.histogram(por_user, x="treinos", nbins=20, labels={"treinos": "treinos no período"}), 360)

    st.subheader("Retenção por coorte de cadastro")
    ret = cohort_retention(u, h, hoje)
    if ret.empty:
        st.info("Retenção precisa da data de cadastro (fonte: banco de dados ou demo).")
    else:
        fig = px.imshow(ret, text_auto=".0f", aspect="auto", color_continuous_scale="Blues",
                        labels=dict(x="Semanas desde o cadastro", y="Coorte (semana de cadastro)", color="% ativos"))
        plot(fig, max(300, 40 * len(ret) + 120))
        st.caption("Cada célula = % dos usuários da coorte com ≥1 treino na semana indicada. Treinos feitos offline antes do cadastro contam na semana 0.")


def aba_desempenho(hp, d, ini, fim):
    if hp.empty:
        st.info("Nenhum treino no período.")
        return
    col = "dia" if (fim - ini).days <= 31 else "semana"
    st.caption(f"Agregação por {'dia' if col == 'dia' else 'semana'}.")
    g = hp.groupby(col).agg(precisao=("precisao", "mean"), pontuacao=("pontuacao", "mean"),
                            vel_media=("vel_media", "mean"), vel_max=("vel_max", "max"),
                            saques=("saques", "sum"), forehands=("forehands", "sum"),
                            backhands=("backhands", "sum")).reset_index()
    c1, c2 = st.columns(2)
    with c1:
        plot(px.line(g, x=col, y="precisao", markers=True, title="Precisão média (%)"))
    with c2:
        plot(px.line(g, x=col, y=["vel_media", "vel_max"], markers=True, title="Velocidade da bola (km/h)"))
    c1, c2 = st.columns(2)
    with c1:
        plot(px.bar(g, x=col, y=["saques", "forehands", "backhands"], title="Golpes por tipo"))
    with c2:
        v = hp[hp["vel_max"].notna()]
        if v.empty:
            st.caption("Sem leituras de velocidade no período.")
        else:
            plot(px.histogram(v, x="vel_max", nbins=30, title="Distribuição da velocidade máxima por sessão",
                              labels={"vel_max": "km/h"}))

    st.subheader("Desempenho por treino")
    nomes = d.set_index("id")["nome"].to_dict()
    t = hp.assign(treino=hp["treino_id"].map(lambda x: nomes.get(x, x)))
    tab = t.groupby("treino").agg(execucoes=("sync_id", "count"), usuarios=("usuario_id", "nunique"),
                                  conclusao=("concluido", "mean"), minutos_medios=("minutos", "mean"),
                                  precisao=("precisao", "mean"), pontuacao=("pontuacao", "mean"),
                                  bolas=("bolas_detectadas", "sum"), acima_meta=("bolas_acima_meta", "sum"))
    tab["conclusao"] *= 100
    tab["% bolas acima da meta"] = (tab["acima_meta"] / tab["bolas"].where(tab["bolas"] > 0) * 100)
    tab = tab.drop(columns=["bolas", "acima_meta"]).sort_values("execucoes", ascending=False).round(1)
    st.dataframe(tab, column_config={
        "conclusao": st.column_config.ProgressColumn("conclusão %", min_value=0, max_value=100, format="%.0f"),
        "precisao": st.column_config.NumberColumn("precisão %"),
        "minutos_medios": st.column_config.NumberColumn("min. médios")})


def aba_desafios(h, d, u):
    if d.empty:
        st.info("Nenhum desafio personalizado criado ainda.")
        return
    ativos = d[~d["excluido"]]
    usados = set(h["treino_id"])
    nunca = ativos[~ativos["id"].isin(usados)]
    c = st.columns(5)
    kpi(c[0], "Desafios ativos", fmt(len(ativos)))
    kpi(c[1], "Excluídos", fmt(int(d["excluido"].sum())))
    kpi(c[2], "Criadores", fmt(ativos["usuario_id"].nunique()))
    kpi(c[3], "Média por criador", fmt(len(ativos) / max(ativos["usuario_id"].nunique(), 1), 1))
    kpi(c[4], "Nunca executados", fmt(len(nunca) / max(len(ativos), 1) * 100, 0, "%"))
    c1, c2 = st.columns(2)
    with c1:
        cat = ativos["categoria"].fillna("—").value_counts().rename_axis("categoria").reset_index(name="desafios")
        plot(px.bar(cat, x="categoria", y="desafios", title="Desafios por categoria"))
    with c2:
        dif = ativos["dificuldade"].fillna("—").value_counts().rename_axis("dificuldade").reset_index(name="desafios")
        plot(px.pie(dif, names="dificuldade", values="desafios", hole=0.5, title="Dificuldade"))
    c1, c2 = st.columns(2)
    with c1:
        s = ativos.dropna(subset=["criado"]).groupby(ativos["criado"].dt.to_period("W-SUN").dt.start_time).size()
        plot(px.bar(s.rename("criados").reset_index().rename(columns={"criado": "semana"}),
                    x="semana", y="criados", title="Desafios criados por semana"))
    with c2:
        plot(px.histogram(ativos, x="duracao_minutos", nbins=15, title="Duração planejada (min)"))
    st.subheader("Desafios nunca executados")
    st.dataframe(nunca[["nome", "categoria", "dificuldade", "duracao_minutos", "criado"]].sort_values("criado", ascending=False))


def aba_gamificacao(u, mostrar_pii):
    if u["xp"].notna().sum() == 0:
        st.info("Nenhum dado de gamificação sincronizado ainda.")
        return
    c = st.columns(4)
    kpi(c[0], "XP médio", fmt(u["xp"].mean(), 0))
    kpi(c[1], "Maior combo", fmt(u["maior_combo"].max(), 0))
    kpi(c[2], "Chefões derrotados", fmt(u["chefoes"].sum(), 0))
    kpi(c[3], "Recorde de velocidade", fmt(u["pr_vel"].max(), 0, " km/h"))
    c1, c2 = st.columns(2)
    with c1:
        plot(px.histogram(u.dropna(subset=["xp"]), x="xp", nbins=20, title="Distribuição de XP de carreira"))
    with c2:
        plot(px.scatter(u.dropna(subset=["xp", "sessoes"]), x="sessoes", y="xp", hover_name="nome",
                        title="XP × sessões (cada ponto = um usuário)"))
    st.subheader("Ranking")
    rk = u.sort_values("xp", ascending=False).head(15)[["nome", "xp", "sessoes", "maior_combo", "chefoes", "pr_vel", "pr_forca"]]
    st.dataframe(rk.rename(columns={"maior_combo": "maior combo", "pr_vel": "PR velocidade", "pr_forca": "PR força"}))


def aba_usuarios(modo, cfg_t, h, u, hoje, mostrar_pii):
    r = resumo_usuarios(u, h, hoje)
    st_counts = r["status"].value_counts().rename_axis("status").reset_index(name="usuários")
    c1, c2 = st.columns([1, 2])
    with c1:
        plot(px.pie(st_counts, names="status", values="usuários", hole=0.5, title="Status dos usuários"), 320)
    with c2:
        if u["idade"].notna().any():
            faixas = pd.cut(u["idade"], [0, 17, 24, 34, 44, 54, 100], labels=["<18", "18–24", "25–34", "35–44", "45–54", "55+"])
            fx = faixas.value_counts().sort_index().rename_axis("faixa").reset_index(name="usuários")
            plot(px.bar(fx, x="faixa", y="usuários", title="Faixa etária (informada no cadastro)"), 320)
    tabela = r[["id", "nome", "email", "criado", "ultima", "treinos", "minutos", "precisao", "xp", "status"]].copy()
    if not mostrar_pii:
        tabela["email"] = tabela["email"].map(mask_email)
    tabela = tabela.sort_values("ultima", ascending=False)
    st.dataframe(tabela.round(1), hide_index=True)
    st.download_button("⬇️ Baixar CSV", tabela.to_csv(index=False).encode("utf-8"), "usuarios_smartace.csv", "text/csv")

    st.subheader("Detalhe de um usuário")
    ids = r["id"].tolist()
    if not ids:
        return
    nomes = r.set_index("id")["nome"].to_dict()
    uid = st.selectbox("Usuário", ids, format_func=lambda i: f"#{i} — {nomes.get(i)}")
    sess = h[h["usuario_id"] == uid].sort_values("data", ascending=False).reset_index(drop=True)
    if sess.empty:
        st.info("Este usuário ainda não sincronizou treinos.")
        return
    plot(px.line(sess.sort_values("data"), x="data", y=["precisao", "vel_max"], markers=True,
                 title="Evolução do usuário (precisão % e velocidade máx.)"), 300)
    st.dataframe(sess[["data", "treino_id", "minutos", "pontuacao", "golpes_corretos", "total_golpes",
                       "precisao", "bolas_detectadas", "bolas_acima_meta", "vel_max", "concluido"]].round(1))
    i = st.selectbox("Sessão para ver métricas da câmera", sess.index,
                     format_func=lambda k: f"{sess.loc[k, 'data']:%d/%m/%Y %H:%M} — {sess.loc[k, 'treino_id']}")
    if st.button("Carregar métricas da câmera"):
        row = sess.loc[i]
        raw = fetch_camera_json(modo, cfg_t, row["id"], row.get("metricas_camera_json"))
        df, err = camera_df(raw)
        if err:
            st.warning(err)
        else:
            st.dataframe(df)
            num = df.select_dtypes("number").columns.tolist()
            if num:
                sel = st.multiselect("Colunas para plotar", num, default=num[:2])
                if sel:
                    plot(px.line(df.reset_index(), x="index", y=sel, markers=True), 300)
        with st.expander("JSON bruto"):
            st.code(str(raw)[:5000])


def aba_saude(modo, cfg_t, api_url, h, agora):
    st.subheader("API")
    if modo == "demo":
        st.caption("Modo demonstração: sem backend real.")
    else:
        hl = api_health(api_url)
        c = st.columns(4)
        kpi(c[0], "Status", "🟢 online" if hl["ok"] else "🔴 offline")
        kpi(c[1], "Latência", fmt(hl["ms"], 0, " ms"))
        kpi(c[2], "Banco (segundo a API)", str(hl["data"].get("database", "—")))
        skew = (hl["data"]["time"] / 1000 - time.time()) if hl["data"].get("time") else None
        kpi(c[3], "Diferença de relógio", fmt(skew, 1, " s") if skew is not None else "—")
        if hl.get("error"):
            st.error(hl["error"])
    if modo == "db":
        st.subheader("Volume de dados")
        cnt = db_counts(cfg_t)
        c = st.columns(3)
        kpi(c[0], "Usuários", fmt(cnt["usuario"]))
        kpi(c[1], "Sessões de treino", fmt(cnt["historico_treino"]))
        kpi(c[2], "Desafios", fmt(cnt["desafio_personalizado"]))

    st.subheader("Sincronização")
    if h["recebido"].notna().any():
        ultimo = h["recebido"].max()
        c = st.columns(3)
        kpi(c[0], "Último sync recebido", f"{ultimo:%d/%m %H:%M}")
        lag = h["atraso_sync_h"].dropna()
        kpi(c[1], "Atraso mediano (treino → servidor)", fmt(lag.median(), 1, " h"))
        kpi(c[2], "Atraso p90", fmt(lag.quantile(0.9), 1, " h"))
        por_dia = h.groupby(h["recebido"].dt.normalize()).size().rename("sessões recebidas").reset_index()
        plot(px.bar(por_dia, x="recebido", y="sessões recebidas", title="Sessões recebidas por dia"), 280)
        st.caption("Atraso alto = muito uso offline. Valores negativos indicam que DB_TIMESTAMP_TZ está errado.")
    else:
        st.caption("A API não expõe recebido_em; use a fonte 'Banco de dados' para métricas de sincronização.")

    st.subheader("Qualidade dos dados")
    st.dataframe(qualidade(h, agora), hide_index=True)


# --------------------------------------------------------------------------------------
# App
# --------------------------------------------------------------------------------------
def main():
    gate()
    st.title("🎾 Smart Ace — Monitoramento de uso")
    hoje = pd.Timestamp.now(tz=TZ).tz_localize(None).normalize()
    agora = pd.Timestamp.now(tz=TZ).tz_localize(None)

    with st.sidebar:
        st.header("Fonte dos dados")
        fonte = st.radio("Fonte", [FONTE_DB, FONTE_API, FONTE_DEMO], label_visibility="collapsed")
        api_url = st.text_input("URL da API", value=cfg("API_URL", "http://127.0.0.1:5001"))
        tz_db = st.text_input("Fuso dos TIMESTAMP do MySQL", value=cfg("DB_TIMESTAMP_TZ", "UTC"),
                              help="Usado em criado_em/recebido_em. Treinos usam epoch (ms) e não dependem disso.")
        if fonte != FONTE_DEMO:
            hl = api_health(api_url)
            st.caption(f"{'🟢' if hl['ok'] else '🔴'} API {'online' if hl['ok'] else 'offline'}"
                       + (f" · {hl['ms']:.0f} ms" if hl["ms"] else ""))

        cfg_t = None
        if fonte == FONTE_DB:
            with st.expander("Conexão MySQL", expanded=not cfg("DB_USER")):
                host = st.text_input("Host", cfg("DB_HOST", "127.0.0.1"))
                port = st.text_input("Porta", cfg("DB_PORT", "3306"))
                user = st.text_input("Usuário (somente leitura)", cfg("DB_USER", "smarttennis_dashboard"))
                pw_in = st.text_input("Senha (vazio = usar .env)", type="password")
                dbn = st.text_input("Banco", cfg("DB_NAME", "smarttennis_backend"))
                use_ssl = st.checkbox("Usar SSL", value=cfg("DB_SSL", "true").lower() != "false")
            cfg_t = (host, port, user, pw_in or cfg("DB_PASSWORD"), dbn, use_ssl)

        if fonte == FONTE_API:
            with st.expander("Login da conta", expanded="api_token" not in st.session_state):
                em = st.text_input("E-mail")
                sn = st.text_input("Senha", type="password")
                if st.button("Entrar"):
                    try:
                        tok, usr = api_login(api_url, em, sn)
                        st.session_state["api_token"], st.session_state["api_user"] = tok, usr
                        st.rerun()
                    except Exception as e:  # noqa: BLE001
                        st.error(str(e))
                if "api_token" in st.session_state and st.button("Sair"):
                    st.session_state.pop("api_token", None)
                    st.session_state.pop("api_user", None)
                    st.rerun()

    # ---- carga ----
    modo = {FONTE_DB: "db", FONTE_API: "api", FONTE_DEMO: "demo"}[fonte]
    try:
        if modo == "db":
            data = load_db(cfg_t, tz_db)
        elif modo == "api":
            if "api_token" not in st.session_state:
                st.info("Entre com e-mail e senha de uma conta na barra lateral para carregar os dados via API.")
                st.stop()
            data = load_api(api_url, st.session_state["api_token"], st.session_state["api_user"], tz_db)
        else:
            data = load_demo()
    except PermissionError as e:
        st.session_state.pop("api_token", None)
        st.error(str(e))
        st.stop()
    except Exception as e:  # noqa: BLE001
        st.error(f"Não foi possível carregar os dados: {e}")
        st.caption("Confira host/porta/credenciais, se o usuário tem SELECT nas tabelas (veja grant_readonly.sql), "
                   "se o SSL está correto e se o db.sql já foi executado.")
        st.stop()
    u, h, d = data["u"], data["h"], data["d"]

    with st.sidebar:
        st.header("Filtros")
        preset = st.selectbox("Período", ["Últimos 7 dias", "Últimos 30 dias", "Últimos 90 dias", "Tudo", "Personalizado"], index=1)
        if preset == "Personalizado":
            rng = st.date_input("Intervalo", value=(hoje - 29 * DIA, hoje))
            rng = rng if isinstance(rng, (tuple, list)) else (rng,)
            ini = pd.Timestamp(rng[0])
            fim = pd.Timestamp(rng[-1])
        elif preset == "Tudo":
            ini = h["dia"].min() if not h.empty else hoje - 29 * DIA
            fim = hoje
        else:
            ini = hoje - (int(preset.split()[1]) - 1) * DIA
            fim = hoje
        mostrar_pii = st.checkbox("Mostrar e-mails completos", value=False,
                                  help="Por padrão os e-mails ficam mascarados (LGPD).")
        if st.button("🔄 Atualizar dados"):
            st.cache_data.clear()
            st.rerun()
        st.caption(f"Dados carregados às {time.strftime('%H:%M:%S', time.localtime(data['loaded_at']))} "
                   "(cache de 60 s).")

    hp = filtrar_periodo(h, ini, fim)
    hprev = None
    if preset != "Tudo":
        n_dias = (fim - ini).days + 1
        hprev = filtrar_periodo(h, ini - n_dias * DIA, ini - DIA)
    st.caption(f"Período: {ini:%d/%m/%Y} a {fim:%d/%m/%Y}" + ("" if hprev is None else " · variação vs. período anterior de mesmo tamanho"))

    abas = st.tabs(["📊 Visão geral", "🔁 Engajamento", "🎯 Desempenho", "🧩 Desafios", "🏆 Gamificação", "👥 Usuários", "🩺 Saúde do backend"])
    with abas[0]:
        aba_visao_geral(h, hp, hprev, u, d, ini, fim)
    with abas[1]:
        aba_engajamento(h, hp, u, d, fim, hoje)
    with abas[2]:
        aba_desempenho(hp, d, ini, fim)
    with abas[3]:
        aba_desafios(h, d, u)
    with abas[4]:
        aba_gamificacao(u, mostrar_pii)
    with abas[5]:
        aba_usuarios(modo, cfg_t, h, u, hoje, mostrar_pii)
    with abas[6]:
        aba_saude(modo, cfg_t, api_url, h, agora)


if __name__ == "__main__":
    main()
