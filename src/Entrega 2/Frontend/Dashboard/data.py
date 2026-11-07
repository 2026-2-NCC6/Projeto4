# -*- coding: utf-8 -*-
"""Carga de dados (API do backend / demo) e cálculo de métricas do dashboard Smart Ace.

O dashboard NÃO acessa o banco: tudo vem das rotas /api/admin/* do backend (server.js).
"""
from __future__ import annotations

import json
import time

import numpy as np
import pandas as pd
import requests
import streamlit as st
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import config

TZ = "America/Sao_Paulo"
DOW = ["Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"]
DOW_FULL = ["segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado", "domingo"]
DIA = pd.Timedelta(days=1)
MAX_METRICAS_CHARS = 200_000  # limite aplicado pelo server.js ao metricasCameraJson

# --------------------------------------------------------------------------------------
# Contrato com a API do backend (rotas /api/admin/*) -> colunas usadas pelo dashboard
# --------------------------------------------------------------------------------------
HIST_COLS = ["id", "usuario_id", "sync_id", "treino_id", "data_conclusao_ms", "tempo_segundos",
             "pontuacao", "saques", "forehands", "backhands", "golpes_corretos", "total_golpes",
             "golpes_recebidos", "concluido", "bolas_detectadas", "bolas_acima_meta",
             "velocidade_maxima_kmh", "velocidade_media_kmh", "metricas_chars", "recebido_em_ms"]
DES_COLS = ["id", "usuario_id", "nome", "descricao", "duracao_minutos", "dificuldade", "categoria",
            "sequencia_golpes", "criado_em_ms", "atualizado_em_ms", "velocidade_alvo_kmh",
            "bolas_alvo", "excluido"]
USER_COLS = ["id", "nome", "email", "data_nascimento", "gamificacao_json", "criado_em_ms", "atualizado_em_ms"]

API_USER_MAP = {"id": "id", "nome": "nome", "email": "email", "dataNascimento": "data_nascimento",
                "gamificacaoJson": "gamificacao_json", "criadoEmMs": "criado_em_ms",
                "atualizadoEmMs": "atualizado_em_ms"}
API_HIST_MAP = {"id": "id", "usuarioId": "usuario_id", "syncId": "sync_id", "treinoId": "treino_id",
                "dataConclusao": "data_conclusao_ms", "tempoSegundos": "tempo_segundos",
                "pontuacao": "pontuacao", "saques": "saques", "forehands": "forehands",
                "backhands": "backhands", "golpesCorretos": "golpes_corretos",
                "totalGolpes": "total_golpes", "golpesRecebidos": "golpes_recebidos",
                "concluido": "concluido", "bolasDetectadas": "bolas_detectadas",
                "bolasAcimaMeta": "bolas_acima_meta", "velocidadeMaximaKmh": "velocidade_maxima_kmh",
                "velocidadeMediaKmh": "velocidade_media_kmh", "metricasChars": "metricas_chars",
                "recebidoEmMs": "recebido_em_ms"}
API_DES_MAP = {"id": "id", "usuarioId": "usuario_id", "nome": "nome", "descricao": "descricao",
               "duracaoMinutos": "duracao_minutos", "dificuldade": "dificuldade", "categoria": "categoria",
               "sequenciaGolpes": "sequencia_golpes", "criadoEm": "criado_em_ms",
               "atualizadoEm": "atualizado_em_ms", "velocidadeAlvoKmh": "velocidade_alvo_kmh",
               "bolasAlvo": "bolas_alvo", "excluido": "excluido"}

PAGE_SIZE = 2000      # itens por requisição (o backend aceita até 5000)
MAX_PAGES = 500       # trava de segurança contra paginação infinita


# --------------------------------------------------------------------------------------
# Tratamento
# --------------------------------------------------------------------------------------
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


def faixa_etaria(idade: pd.Series) -> pd.Series:
    f = pd.cut(idade, [0, 17, 24, 34, 44, 54, 120], labels=["<18", "18–24", "25–34", "35–44", "45–54", "55+"])
    return f.astype("object")


def prep_usuarios(u: pd.DataFrame) -> pd.DataFrame:
    u = u.reindex(columns=USER_COLS).copy()
    u["criado"] = ms_local(u["criado_em_ms"])
    nasc = parse_data_nasc(u["data_nascimento"])
    agora = pd.Timestamp.now(tz=TZ).tz_localize(None)
    idade = (agora - nasc).dt.days / 365.25
    u["idade"] = idade.where((idade >= 5) & (idade <= 100))
    u["faixa"] = faixa_etaria(u["idade"])
    g = pd.DataFrame(u["gamificacao_json"].map(parse_gamif).tolist(), index=u.index,
                     columns=["xp", "sessoes", "maior_combo", "chefoes", "pr_vel", "pr_forca"])
    return pd.concat([u, g], axis=1)


def prep_historico(h: pd.DataFrame) -> pd.DataFrame:
    extra = [c for c in h.columns if c not in HIST_COLS]
    h = h.reindex(columns=HIST_COLS + extra).copy()
    for c in ["tempo_segundos", "pontuacao", "saques", "forehands", "backhands", "golpes_corretos",
              "total_golpes", "golpes_recebidos", "bolas_detectadas", "bolas_acima_meta",
              "velocidade_maxima_kmh", "velocidade_media_kmh", "metricas_chars"]:
        h[c] = pd.to_numeric(h[c], errors="coerce").fillna(0)
    h["concluido"] = h["concluido"].fillna(0).astype(bool)
    h["data"] = ms_local(h["data_conclusao_ms"])
    h = h[h["data"].notna()].sort_values("data").copy()
    h["dia"] = h["data"].dt.normalize()
    h["hora"] = h["data"].dt.hour
    h["dow"] = h["data"].dt.dayofweek
    h["semana"] = h["data"].dt.to_period("W-SUN").dt.start_time
    h["periodo_dia"] = pd.cut(h["hora"], [-1, 5, 11, 17, 23],
                              labels=["Madrugada", "Manhã", "Tarde", "Noite"]).astype("object")
    h["minutos"] = h["tempo_segundos"] / 60
    h["precisao"] = (h["golpes_corretos"] / h["total_golpes"].where(h["total_golpes"] > 0) * 100).clip(0, 100)
    h["golpes_min"] = h["total_golpes"] / h["minutos"].where(h["minutos"] > 0)
    h["pct_acima_meta"] = h["bolas_acima_meta"] / h["bolas_detectadas"].where(h["bolas_detectadas"] > 0) * 100
    h["vel_max"] = h["velocidade_maxima_kmh"].where(h["velocidade_maxima_kmh"] > 0)
    h["vel_media"] = h["velocidade_media_kmh"].where(h["velocidade_media_kmh"] > 0)
    h["recebido"] = ms_local(h["recebido_em_ms"])
    h["atraso_sync_h"] = (h["recebido"] - h["data"]).dt.total_seconds() / 3600
    h["n_sessao"] = h.groupby("usuario_id").cumcount() + 1
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


# --------------------------------------------------------------------------------------
# Carga (API do backend)
# --------------------------------------------------------------------------------------
class ApiError(RuntimeError):
    """Falha ao falar com o backend (mensagem já pronta para mostrar ao usuário)."""


def _base() -> str:
    return config.API_URL.rstrip("/")


@st.cache_resource(show_spinner=False)
def _session(key: str) -> requests.Session:
    s = requests.Session()
    s.headers.update({"X-Dashboard-Key": key, "Accept": "application/json"})
    retry = Retry(total=2, backoff_factor=0.5, status_forcelist=(502, 503, 504), allowed_methods=("GET",))
    s.mount("http://", HTTPAdapter(max_retries=retry))
    s.mount("https://", HTTPAdapter(max_retries=retry))
    return s


def _get(path: str, **params):
    if not config.API_KEY:
        raise ApiError("DASHBOARD_API_KEY não configurada. Defina a mesma chave do .env do backend.")
    try:
        r = _session(config.API_KEY).get(f"{_base()}{path}", params=params or None, timeout=(5, 60))
    except requests.RequestException as e:
        raise ApiError(f"Não foi possível conectar ao backend em {_base()} ({e.__class__.__name__}).") from e
    if r.status_code in (401, 503):
        try:
            msg = r.json().get("error", r.text)
        except Exception:  # noqa: BLE001
            msg = r.text
        raise ApiError(f"Backend recusou o acesso ({r.status_code}): {msg}")
    if not r.ok:
        raise ApiError(f"Backend respondeu {r.status_code} em {path}.")
    try:
        return r.json()
    except ValueError as e:
        raise ApiError(f"Resposta inválida do backend em {path}.") from e


def _paginar(path: str) -> list:
    itens, after = [], None
    for _ in range(MAX_PAGES):
        params = {"limit": PAGE_SIZE}
        if after is not None:
            params["afterId"] = after
        page = _get(path, **params)
        itens.extend(page.get("items", []))
        after = page.get("nextAfterId")
        if after is None:
            return itens
    raise ApiError(f"Paginação de {path} excedeu {MAX_PAGES} páginas.")


def _frame(itens: list, mapa: dict, cols: list) -> pd.DataFrame:
    return pd.DataFrame(itens).rename(columns=mapa).reindex(columns=cols)


@st.cache_data(ttl=config.CACHE_TTL, show_spinner="Consultando o backend…")
def load_api() -> dict:
    u = _frame(_paginar("/api/admin/usuarios"), API_USER_MAP, USER_COLS)
    h = _frame(_paginar("/api/admin/historico"), API_HIST_MAP, HIST_COLS)
    d = _frame(_paginar("/api/admin/desafios"), API_DES_MAP, DES_COLS)
    return {"u": prep_usuarios(u), "h": prep_historico(h), "d": prep_desafios(d), "loaded_at": time.time()}


@st.cache_data(ttl=config.CACHE_TTL, show_spinner=False)
def api_counts() -> dict:
    return _get("/api/admin/counts")


def fetch_camera_json(modo: str, hist_id, inline):
    """Na API busca o JSON da câmera sob demanda; no modo demo ele já está no DataFrame."""
    if modo == "demo":
        return inline
    return _get(f"/api/admin/historico/{int(hist_id)}/camera").get("metricasCameraJson")


@st.cache_data(ttl=10, show_spinner=False)
def api_health() -> dict:
    t0 = time.perf_counter()
    try:
        r = requests.get(f"{_base()}/api/health", timeout=8)
        ms = (time.perf_counter() - t0) * 1000
        data = r.json() if "json" in r.headers.get("content-type", "") else {}
        return {"ok": bool(r.ok and data.get("ok")), "status": r.status_code, "ms": ms, "data": data}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "status": None, "ms": None, "data": {}, "error": str(e)}


@st.cache_data(show_spinner=False)
def load_demo(seed: int = 7) -> dict:
    rng = np.random.default_rng(seed)
    agora = pd.Timestamp.now(tz=TZ).tz_localize(None)
    n = 60
    criado = agora - pd.to_timedelta(rng.integers(2, 170, n), unit="D") - pd.to_timedelta(rng.integers(0, 86400, n), unit="s")
    nivel = rng.uniform(0.45, 0.8, n)
    engaj = rng.gamma(1.1, 9, n)
    builtin = ["saque-basico", "forehand-cross", "backhand-linha", "rally-continuo", "velocidade-max"]
    cats = ["SAQUE", "FOREHAND", "BACKHAND", "VELOCIDADE", "PERSONALIZADO"]
    difs = ["Iniciante", "Intermediário", "Avançado"]

    des = []
    for i in range(22):
        uid = int(rng.integers(1, n + 1))
        c = agora - pd.to_timedelta(rng.integers(1, 120), unit="D")
        ms = int(c.tz_localize(TZ).timestamp() * 1000)
        des.append({"id": f"demo-desafio-{i:02d}", "usuario_id": uid, "nome": f"Desafio {i + 1}",
                    "descricao": "Desafio fictício", "duracao_minutos": int(rng.integers(3, 25)),
                    "dificuldade": rng.choice(difs), "categoria": rng.choice(cats),
                    "sequencia_golpes": "F,B,S", "criado_em_ms": ms, "atualizado_em_ms": ms,
                    "velocidade_alvo_kmh": float(rng.choice([0, 80, 100, 120])),
                    "bolas_alvo": int(rng.choice([0, 20, 40])), "excluido": int(rng.random() < 0.12)})
    custom_ids = [x["id"] for x in des]
    pool = builtin * 4 + custom_ids

    hist, k = [], 0
    for i in range(n):
        for _ in range(int(rng.poisson(engaj[i]))):
            dias = min(rng.exponential(35), max((agora - criado[i]).days, 0))
            hora = int(rng.choice([7, 8, 9, 10, 17, 18, 19, 20, 21], p=[.08, .1, .08, .06, .1, .18, .2, .12, .08]))
            dt = (criado[i] + pd.Timedelta(days=float(dias))).normalize() + pd.Timedelta(hours=hora, minutes=int(rng.integers(0, 60)))
            if dt > agora:
                dt = agora - pd.Timedelta(hours=1)
            total = int(rng.integers(20, 200))
            corretos = int(rng.binomial(total, min(nivel[i] + dias / 300, 0.95)))
            sq = int(total * rng.uniform(0.1, 0.3))
            fh = int((total - sq) * rng.uniform(0.4, 0.6))
            bh = total - sq - fh
            bolas = int(rng.integers(10, 80))
            vmax = float(max(0, rng.normal(95, 18))) if rng.random() > 0.15 else 0.0
            cam = json.dumps([{"bola": b + 1, "velocidadeKmh": round(float(rng.normal(vmax * 0.85 + 1, 10)), 1),
                               "acimaMeta": bool(rng.random() < 0.4)} for b in range(min(bolas, 25))])
            lag = float(rng.exponential(4)) if rng.random() < 0.3 else float(rng.uniform(0.01, 0.2))
            k += 1
            hist.append({"id": k, "usuario_id": i + 1, "sync_id": f"demo-{k}", "treino_id": str(rng.choice(pool)),
                         "data_conclusao_ms": int(dt.tz_localize(TZ).timestamp() * 1000),
                         "tempo_segundos": int(rng.integers(300, 2400)),
                         "pontuacao": int(corretos * rng.uniform(8, 14)),
                         "saques": sq, "forehands": fh, "backhands": bh, "golpes_corretos": corretos,
                         "total_golpes": total, "golpes_recebidos": int(rng.integers(0, 40)),
                         "concluido": int(rng.random() < 0.8), "bolas_detectadas": bolas,
                         "bolas_acima_meta": int(rng.binomial(bolas, 0.4)),
                         "velocidade_maxima_kmh": vmax, "velocidade_media_kmh": vmax * 0.82,
                         "metricas_camera_json": cam, "metricas_chars": len(cam),
                         "recebido_em_ms": int((dt + pd.Timedelta(hours=lag)).tz_localize(TZ).timestamp() * 1000)})
    h = pd.DataFrame(hist)
    xp = h.groupby("usuario_id").pontuacao.sum().reindex(range(1, n + 1), fill_value=0)
    u = pd.DataFrame({
        "id": range(1, n + 1), "nome": [f"Jogador {i:02d}" for i in range(1, n + 1)],
        "email": [f"jogador{i:02d}@exemplo.com" for i in range(1, n + 1)],
        "data_nascimento": [f"{int(rng.integers(1, 28)):02d}/{int(rng.integers(1, 12)):02d}/{int(rng.integers(1965, 2012))}" for _ in range(n)],
        "gamificacao_json": [json.dumps({"xpCarreira": int(x), "recordes": (
            f"xp={int(x)};sessoes={int(rng.integers(1, 60))};maiorCombo={int(rng.integers(3, 40))};"
            f"chefoes={int(rng.integers(0, 6))};prVel={int(rng.integers(70, 140))};prForca={int(rng.integers(20, 90))};")})
            for x in xp],
        "criado_em_ms": [int(c.tz_localize(TZ).timestamp() * 1000) for c in criado],
        "atualizado_em_ms": [int(c.tz_localize(TZ).timestamp() * 1000) for c in criado]})
    return {"u": prep_usuarios(u), "h": prep_historico(h), "d": prep_desafios(pd.DataFrame(des)),
            "loaded_at": time.time()}


# --------------------------------------------------------------------------------------
# Métricas (funções puras)
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
    ativos = hp["usuario_id"].nunique()
    minutos = hp["minutos"].sum()
    bolas = hp["bolas_detectadas"].sum()
    return {
        "treinos": n, "ativos": ativos, "horas": minutos / 60,
        "sess_por_ativo": n / ativos if ativos else np.nan,
        "min_medio": hp["minutos"].mean() if n else np.nan,
        "conclusao": hp["concluido"].mean() * 100 if n else np.nan,
        "precisao": hp["precisao"].mean() if n else np.nan,
        "golpes": int(hp["total_golpes"].sum()),
        "golpes_min": hp["total_golpes"].sum() / minutos if minutos else np.nan,
        "vel_media": hp["vel_media"].mean() if n else np.nan,
        "vel_max": hp["vel_max"].max() if n else np.nan,
        "acima_meta": hp["bolas_acima_meta"].sum() / bolas * 100 if bolas else np.nan,
        "bolas": int(bolas),
        "pontuacao": hp["pontuacao"].mean() if n else np.nan,
    }


def ativos_janela(h: pd.DataFrame, fim, dias: int) -> int:
    ini = fim - pd.Timedelta(days=dias - 1)
    return h.loc[(h["data"] >= ini) & (h["data"] < fim + DIA), "usuario_id"].nunique()


def media_movel(s: pd.Series, n: int = 7) -> pd.Series:
    return s.rolling(n, min_periods=1).mean()


def cohort_retention(u: pd.DataFrame, h: pd.DataFrame, hoje, max_weeks: int = 8) -> pd.DataFrame:
    """% de usuários de cada coorte (semana de cadastro) com ≥1 treino na semana k após o cadastro."""
    uu = u.loc[u["criado"].notna(), ["id", "criado"]].copy()
    if uu.empty or h.empty:
        return pd.DataFrame()
    uu["coorte"] = uu["criado"].dt.to_period("W-SUN").dt.start_time
    m = h[["usuario_id", "data"]].merge(uu, left_on="usuario_id", right_on="id")
    m["sem"] = ((m["data"].dt.normalize() - m["criado"].dt.normalize()).dt.days // 7).clip(lower=0)
    m = m[m["sem"] <= max_weeks]
    ativos = m.groupby(["coorte", "sem"])["usuario_id"].nunique().unstack(fill_value=0)
    tam = uu.groupby("coorte")["id"].nunique()
    ativos = ativos.reindex(index=tam.index, columns=range(max_weeks + 1), fill_value=0)
    ret = ativos.div(tam, axis=0) * 100
    idade_sem = ((hoje - ret.index.to_series()).dt.days // 7).to_numpy()
    for k in ret.columns:
        ret.loc[idade_sem < k, k] = np.nan
    ret.index = [f"{c:%d/%m/%y} (n={int(tam[c])})" for c in ret.index]
    return ret


def voltou_em(h: pd.DataFrame, hoje, ns=(1, 7, 30)) -> dict:
    """% de usuários que treinaram em outro dia até N dias após o 1º treino (só quem tem ≥N dias de histórico)."""
    res = {n: np.nan for n in ns}
    if h.empty:
        return res
    d = h.drop_duplicates(["usuario_id", "dia"]).sort_values(["usuario_id", "dia"]).copy()
    d["rk"] = d.groupby("usuario_id").cumcount()
    prim = d.loc[d["rk"] == 0].set_index("usuario_id")["dia"]
    seg = d.loc[d["rk"] == 1].set_index("usuario_id")["dia"]
    for n in ns:
        eleg = prim[prim <= hoje - n * DIA]
        if eleg.empty:
            continue
        gap = (seg.reindex(eleg.index) - eleg).dt.days
        res[n] = float((gap.le(n) & gap.notna()).mean() * 100)
    return res


def maior_sequencia(h: pd.DataFrame) -> pd.Series:
    """Maior sequência de dias consecutivos com treino, por usuário."""
    if h.empty:
        return pd.Series(dtype=float)
    d = h.drop_duplicates(["usuario_id", "dia"])[["usuario_id", "dia"]].sort_values(["usuario_id", "dia"])
    quebra = d.groupby("usuario_id")["dia"].diff().dt.days.ne(1)
    grp = quebra.groupby(d["usuario_id"]).cumsum()
    seq = d.groupby([d["usuario_id"], grp]).size()
    return seq.groupby(level=0).max()


def intervalo_entre_dias(h: pd.DataFrame) -> pd.Series:
    """Dias entre dois dias de treino consecutivos (todos os usuários)."""
    if h.empty:
        return pd.Series(dtype=float)
    d = h.drop_duplicates(["usuario_id", "dia"])[["usuario_id", "dia"]].sort_values(["usuario_id", "dia"])
    return d.groupby("usuario_id")["dia"].diff().dt.days.dropna()


def novos_vs_recorrentes(h: pd.DataFrame) -> pd.DataFrame:
    if h.empty:
        return pd.DataFrame()
    prim = h.groupby("usuario_id")["semana"].min().rename("prim_sem")
    t = h.merge(prim, left_on="usuario_id", right_index=True)
    t["tipo"] = np.where(t["semana"] == t["prim_sem"], "Novos", "Recorrentes")
    return t.groupby(["semana", "tipo"])["usuario_id"].nunique().unstack(fill_value=0).reindex(
        columns=["Novos", "Recorrentes"], fill_value=0)


def pareto_usuarios(hp: pd.DataFrame) -> pd.DataFrame:
    """Concentração de treinos: % acumulado de treinos × % de usuários (ordenado do mais ativo)."""
    if hp.empty:
        return pd.DataFrame()
    c = hp.groupby("usuario_id").size().sort_values(ascending=False)
    out = pd.DataFrame({"pct_usuarios": (np.arange(1, len(c) + 1) / len(c)) * 100,
                        "pct_treinos": c.cumsum().to_numpy() / c.sum() * 100})
    return pd.concat([pd.DataFrame({"pct_usuarios": [0.0], "pct_treinos": [0.0]}), out], ignore_index=True)


def share_top(hp: pd.DataFrame, frac: float = 0.1) -> float:
    if hp.empty:
        return np.nan
    c = hp.groupby("usuario_id").size().sort_values(ascending=False)
    k = max(int(np.ceil(len(c) * frac)), 1)
    return float(c.iloc[:k].sum() / c.sum() * 100)


def curva_aprendizado(h: pd.DataFrame, max_n: int = 15, min_users: int = 3) -> pd.DataFrame:
    if h.empty:
        return pd.DataFrame()
    t = h.assign(n=h["n_sessao"].clip(upper=max_n))
    g = t.groupby("n").agg(precisao=("precisao", "mean"), vel_max=("vel_max", "mean"),
                           pontuacao=("pontuacao", "mean"), usuarios=("usuario_id", "nunique")).reset_index()
    return g[g["usuarios"] >= min_users]


def melhoria_usuarios(h: pd.DataFrame, min_sessoes: int = 4) -> pd.DataFrame:
    rows = []
    for uid, g in h.sort_values("data").groupby("usuario_id"):
        p = g["precisao"].dropna()
        if len(p) < min_sessoes:
            continue
        m = len(p) // 2
        rows.append((uid, p.iloc[:m].mean(), p.iloc[m:].mean(), len(p)))
    df = pd.DataFrame(rows, columns=["usuario_id", "antes", "depois", "sessoes"])
    df["delta"] = df["depois"] - df["antes"]
    return df


def calendario(h: pd.DataFrame, hoje, semanas: int = 26) -> pd.DataFrame:
    """Linhas = dia da semana, colunas = semana (início na segunda). Valores = nº de treinos."""
    ini = (hoje - (semanas - 1) * 7 * DIA).to_period("W-SUN").start_time
    t = h[(h["dia"] >= ini) & (h["dia"] <= hoje)]
    piv = t.pivot_table(index="dow", columns="semana", values="sync_id", aggfunc="count")
    cols = pd.date_range(ini, periods=semanas, freq="7D")
    return piv.reindex(index=range(7), columns=cols).fillna(0)


def correlacao(hp: pd.DataFrame) -> pd.DataFrame:
    cols = {"minutos": "duração", "pontuacao": "pontuação", "total_golpes": "golpes", "precisao": "precisão",
            "vel_max": "vel. máx.", "bolas_detectadas": "bolas", "golpes_recebidos": "recebidos"}
    return hp[list(cols)].rename(columns=cols).corr()


def resumo_usuarios(u: pd.DataFrame, h: pd.DataFrame, hoje) -> pd.DataFrame:
    g = h.groupby("usuario_id").agg(treinos=("sync_id", "count"), minutos=("minutos", "sum"),
                                    precisao=("precisao", "mean"), ultima=("data", "max"),
                                    primeira=("data", "min"), concluidos=("concluido", "sum"),
                                    dias_ativos=("dia", "nunique"))
    r = u.merge(g, left_on="id", right_index=True, how="left")
    r["treinos"] = r["treinos"].fillna(0).astype(int)
    r["dias_ativos"] = r["dias_ativos"].fillna(0).astype(int)
    dias = (hoje - r["ultima"].dt.normalize()).dt.days
    r["dias_sem_treinar"] = dias
    r["status"] = np.select([r["treinos"] == 0, dias <= 7, dias <= 30],
                            ["Nunca treinou", "Ativo (≤7d)", "Em risco (8–30d)"], "Inativo (>30d)")
    return r


def tipo_treino(h: pd.DataFrame, d: pd.DataFrame) -> pd.Series:
    custom = set(d["id"])
    return h["treino_id"].map(lambda x: "Personalizado" if x in custom else "Padrão")


def destaques(hp: pd.DataFrame, d: pd.DataFrame) -> list[tuple[str, str]]:
    """Frases-resumo calculadas sobre o período selecionado."""
    out: list[tuple[str, str]] = []
    if hp.empty:
        return out
    dow = hp.groupby("dow").size()
    out.append(("📅", f"Dia mais movimentado: {DOW_FULL[int(dow.idxmax())]} ({dow.max()} treinos)."))
    hr = hp.groupby("hora").size()
    out.append(("⏰", f"Horário de pico: {int(hr.idxmax())}h, com {hr.max()} treinos."))
    per = hp["periodo_dia"].value_counts()
    out.append(("🌗", f"{per.index[0]} concentra {per.iloc[0] / len(hp) * 100:.0f}% dos treinos."))
    nomes = d.set_index("id")["nome"].to_dict()
    top = hp["treino_id"].map(lambda x: nomes.get(x, x)).value_counts()
    out.append(("🏅", f"Treino mais executado: {top.index[0]} ({top.iloc[0]}×)."))
    s = share_top(hp, 0.1)
    if not np.isnan(s):
        out.append(("👥", f"10% dos usuários mais ativos respondem por {s:.0f}% dos treinos."))
    p = hp.groupby("dow")["precisao"].mean().dropna()
    if not p.empty:
        out.append(("🎯", f"Maior precisão média: {DOW_FULL[int(p.idxmax())]} ({p.max():.1f}%)."))
    return out


def camera_df(raw):
    """Estrutura de metricas_camera_json não é fixa: tenta transformar em tabela."""
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
        "Métricas de câmera truncadas (≥ 200.000 caracteres)": (h["metricas_chars"] >= MAX_METRICAS_CHARS).sum(),
    }
    out = pd.DataFrame({"verificação": list(checks), "ocorrências": [int(v) for v in checks.values()]})
    out["% das sessões"] = (out["ocorrências"] / max(len(h), 1) * 100).round(1)
    return out
