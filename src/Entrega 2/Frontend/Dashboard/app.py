# -*- coding: utf-8 -*-
"""
Smart Ace / SmartTennis — Dashboard de monitoramento e análise de uso do app.

Rodar:  streamlit run app.py
O backend (URL da API e chave) é configurado em config.py/.env — não na interface.
O dashboard não acessa o banco: só a API do backend.
"""
from __future__ import annotations

import html
import itertools
import time
from types import SimpleNamespace

import numpy as np
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
import plotly.io as pio
import streamlit as st

import config
import data as D
from data import DIA, DOW, fmt

st.set_page_config(page_title="Smart Ace · Analytics", page_icon="🎾", layout="wide")

# --------------------------------------------------------------------------------------
# Identidade visual
# --------------------------------------------------------------------------------------
GREEN, LIME, YELLOW, BLUE = "#1B8A5A", "#9BC53D", "#F2C230", "#2D6CDF"
CLAY, PURPLE, TEAL, GRAY = "#E4572E", "#7B61FF", "#12B5CB", "#8D99AE"
COLORS = [GREEN, YELLOW, BLUE, CLAY, PURPLE, TEAL, LIME, GRAY]
SCALE_GREEN = ["#F1F8F4", "#C9E8D6", "#6CBF93", "#1B8A5A", "#0B4F34"]
SCALE_DIV = [[0, CLAY], [0.5, "#F6F9F7"], [1, GREEN]]
STATUS_COLORS = {"Ativo (≤7d)": GREEN, "Em risco (8–30d)": YELLOW, "Inativo (>30d)": CLAY, "Nunca treinou": GRAY}
FONTE_NOME = {"api": "API do backend (visão global)", "demo": "Demonstração (dados fictícios)"}

pio.templates["smartace"] = go.layout.Template(layout=dict(
    colorway=COLORS,
    font=dict(family="Inter, Segoe UI, sans-serif", size=12, color="#344054"),
    paper_bgcolor="rgba(0,0,0,0)", plot_bgcolor="rgba(0,0,0,0)",
    xaxis=dict(gridcolor="#EEF2EF", zeroline=False, linecolor="#D0D5DD"),
    yaxis=dict(gridcolor="#EEF2EF", zeroline=False),
    title=dict(font=dict(size=15, color="#0B3D2E"), x=0.01),
    legend=dict(orientation="h", yanchor="bottom", y=1.0, xanchor="right", x=1, title_text=""),
    hoverlabel=dict(bgcolor="white", font_size=12),
))
pio.templates.default = "smartace"

CSS = """
<style>
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');
html, body, [class*="css"], .stMarkdown, button, input { font-family: 'Inter', 'Segoe UI', sans-serif; }
.block-container { padding-top: 1.1rem; padding-bottom: 3rem; max-width: 1560px; }
header[data-testid="stHeader"] { background: transparent; }
section[data-testid="stSidebar"] { border-right: 1px solid #DCE8E0; }
.hero { background: linear-gradient(115deg,#0B4F34 0%,#1B8A5A 55%,#9BC53D 120%); color:#fff;
        padding: 1.3rem 1.6rem; border-radius: 18px; margin-bottom: 1rem;
        box-shadow: 0 6px 18px rgba(11,79,52,.18); }
.hero h1 { margin:0; font-size:1.65rem; font-weight:700; color:#fff; letter-spacing:-.01em; }
.hero p { margin:.25rem 0 .7rem; opacity:.92; font-size:.95rem; }
.pill { display:inline-block; padding:.18rem .65rem; border-radius:999px; background:rgba(255,255,255,.18);
        font-size:.76rem; margin-right:.4rem; font-weight:500; }
.kpi { background:#fff; border:1px solid #E1EAE4; border-radius:14px; padding:.8rem 1rem .7rem;
       box-shadow:0 1px 2px rgba(16,24,40,.05); margin-bottom:.7rem; }
.kpi .lbl { font-size:.72rem; color:#667085; text-transform:uppercase; letter-spacing:.05em; font-weight:600; }
.kpi .val { font-size:1.65rem; font-weight:700; color:#0B3D2E; line-height:1.25; }
.kpi .dlt { font-size:.78rem; font-weight:600; min-height:1.1rem; }
.dlt.up { color:#12805C; } .dlt.down { color:#C0392B; } .dlt.flat { color:#98A2B3; }
.sec { margin:1.1rem 0 .5rem; }
.sec h3 { margin:0; font-size:1.08rem; color:#0B3D2E; font-weight:700; }
.sec span { color:#667085; font-size:.86rem; }
.insight { background:#fff; border:1px solid #E1EAE4; border-left:4px solid #1B8A5A; border-radius:10px;
           padding:.6rem .85rem; font-size:.9rem; color:#344054; margin-bottom:.6rem; }
.brand { font-size:1.25rem; color:#0B3D2E; margin-bottom:.2rem; }
.brand span { color:#667085; font-size:.8rem; }
.stTabs [data-baseweb="tab-list"] { gap:.25rem; border-bottom:1px solid #DCE8E0; }
.stTabs [data-baseweb="tab"] { padding:.55rem 1rem; border-radius:10px 10px 0 0; font-weight:600; }
div[data-testid="stVerticalBlockBorderWrapper"] { background:#fff; border-radius:14px; }
</style>
"""

_keys = itertools.count()


def plot(fig, height=340):
    fig.update_layout(height=height, margin=dict(l=8, r=8, t=56, b=8))
    with st.container(border=True):
        st.plotly_chart(fig, theme=None, key=f"chart-{next(_keys)}")


def kpi(col, label, valor, delta=None, hint="", icon=""):
    cls, d = "flat", "&nbsp;"
    if delta:
        t = delta.strip()
        cls = "up" if t.startswith("+") else ("down" if t[0] in "-−" else "flat")
        d = html.escape(delta)
    col.markdown(
        f'<div class="kpi" title="{html.escape(hint)}"><div class="lbl">{icon} {html.escape(label)}</div>'
        f'<div class="val">{html.escape(str(valor))}</div><div class="dlt {cls}">{d}</div></div>',
        unsafe_allow_html=True)


def kpi_row(cards):
    cols = st.columns(len(cards))
    for col, c in zip(cols, cards):
        kpi(col, *c)


def section(titulo, sub=""):
    st.markdown(f'<div class="sec"><h3>{html.escape(titulo)}</h3><span>{html.escape(sub)}</span></div>',
                unsafe_allow_html=True)


def insights(items):
    cols = st.columns(3)
    for i, (ic, tx) in enumerate(items):
        cols[i % 3].markdown(f'<div class="insight">{ic} {html.escape(tx)}</div>', unsafe_allow_html=True)


def gate():
    pw = config.DASHBOARD_PASSWORD
    if not pw or st.session_state.get("auth_ok"):
        return
    import hmac
    st.markdown(CSS, unsafe_allow_html=True)
    st.title("🎾 Smart Ace — Analytics")
    typed = st.text_input("Senha de acesso", type="password")
    if typed:
        if hmac.compare_digest(typed, pw):
            st.session_state["auth_ok"] = True
            st.rerun()
        st.error("Senha incorreta.")
    st.stop()


def carregar():
    try:
        if config.FONTE == "api":
            return D.load_api()
        if config.FONTE == "demo":
            return D.load_demo()
        st.error('config.FONTE deve ser "api" ou "demo".')
        st.stop()
    except D.ApiError as e:
        st.error(f"Não foi possível carregar os dados: {e}")
        st.caption("Confira API_URL e DASHBOARD_API_KEY (config.py/.env), se o backend está no ar e se o "
                   "DASHBOARD_API_KEY também está definido no .env do backend.")
        st.stop()
    except Exception as e:  # noqa: BLE001
        st.error(f"Erro inesperado ao processar os dados: {e}")
        st.stop()


# --------------------------------------------------------------------------------------
# Abas
# --------------------------------------------------------------------------------------
def aba_visao_geral(c):
    cur = D.resumo_periodo(c.hp)
    ant = D.resumo_periodo(c.hprev) if c.hprev is not None else None

    def dl(k):
        return D.delta_pct(cur[k], ant[k]) if ant else None

    u = c.u
    tem_criado = u["criado"].notna().any()
    novos = novos_ant = None
    if tem_criado:
        novos = int(((u["criado"] >= c.ini) & (u["criado"] < c.fim + DIA)).sum())
        if c.hprev is not None:
            n = (c.fim - c.ini).days + 1
            novos_ant = int(((u["criado"] >= c.ini - n * DIA) & (u["criado"] < c.ini)).sum())

    kpi_row([
        ("Usuários cadastrados", fmt(len(u)), None, "Total de contas no backend.", "👥"),
        ("Novos usuários", fmt(novos), D.delta_pct(novos, novos_ant) if novos_ant is not None else None, "Cadastros no período.", "✨"),
        ("Usuários ativos", fmt(cur["ativos"]), dl("ativos"), "Usuários com ≥1 treino no período.", "🏃"),
        ("Treinos", fmt(cur["treinos"]), dl("treinos"), "", "🎾"),
        ("Sessões / usuário ativo", fmt(cur["sess_por_ativo"], 1), dl("sess_por_ativo"), "Intensidade de uso.", "🔁"),
    ])
    kpi_row([
        ("Horas treinadas", fmt(cur["horas"], 1), dl("horas"), "", "⏱️"),
        ("Duração média", fmt(cur["min_medio"], 1, " min"), dl("min_medio"), "Tempo médio por sessão.", "⌛"),
        ("Taxa de conclusão", fmt(cur["conclusao"], 1, "%"), dl("conclusao"), "Sessões concluídas ÷ total.", "✅"),
        ("Golpes", fmt(cur["golpes"]), dl("golpes"), "", "🏸"),
        ("Golpes / minuto", fmt(cur["golpes_min"], 1), dl("golpes_min"), "Intensidade do treino.", "⚡"),
    ])
    kpi_row([
        ("Precisão média", fmt(cur["precisao"], 1, "%"), dl("precisao"), "golpes_corretos ÷ total_golpes.", "🎯"),
        ("Velocidade média", fmt(cur["vel_media"], 0, " km/h"), dl("vel_media"), "Só sessões com leitura (>0).", "💨"),
        ("Velocidade máxima", fmt(cur["vel_max"], 0, " km/h"), None, "", "🚀"),
        ("Bolas acima da meta", fmt(cur["acima_meta"], 1, "%"), dl("acima_meta"), "bolas_acima_meta ÷ bolas_detectadas.", "📈"),
        ("Pontuação média", fmt(cur["pontuacao"], 0), dl("pontuacao"), "", "🏆"),
    ])

    if c.hp.empty:
        st.info("Nenhum treino no período selecionado.")
        return

    section("Destaques do período", "Padrões calculados automaticamente")
    insights(D.destaques(c.hp, c.d))

    section("Evolução")
    idx = pd.date_range(c.ini, c.fim, freq="D", name="dia")
    daily = (c.hp.groupby("dia").agg(treinos=("sync_id", "count"), ativos=("usuario_id", "nunique"),
                                     minutos=("minutos", "sum")).reindex(idx, fill_value=0))
    daily["ma7"] = D.media_movel(daily["treinos"])
    daily["horas"] = daily["minutos"] / 60
    c1, c2 = st.columns(2)
    with c1:
        fig = go.Figure()
        fig.add_trace(go.Bar(x=daily.index, y=daily["treinos"], name="Treinos", marker_color=GREEN, opacity=.85))
        fig.add_trace(go.Scatter(x=daily.index, y=daily["ma7"], name="Média móvel 7d", line=dict(color=YELLOW, width=3)))
        fig.update_layout(title="Treinos por dia")
        plot(fig)
    with c2:
        fig = go.Figure()
        fig.add_trace(go.Bar(x=daily.index, y=daily["horas"], name="Horas treinadas", marker_color="#BFE3CD", yaxis="y2"))
        fig.add_trace(go.Scatter(x=daily.index, y=daily["ativos"], name="Usuários ativos", line=dict(color=BLUE, width=3)))
        fig.update_layout(title="Usuários ativos e horas treinadas por dia",
                          yaxis2=dict(overlaying="y", side="right", showgrid=False, title="horas"))
        plot(fig)

    cal = D.calendario(c.h, c.hoje)
    fig = go.Figure(go.Heatmap(z=cal.values, x=cal.columns, y=DOW, colorscale=SCALE_GREEN, xgap=3, ygap=3,
                               showscale=False, hovertemplate="semana de %{x|%d/%m}<br>%{y}: %{z} treinos<extra></extra>"))
    fig.update_yaxes(autorange="reversed")
    fig.update_layout(title="Atividade nas últimas 26 semanas (todos os usuários)")
    plot(fig, 250)

    c1, c2 = st.columns(2)
    with c1:
        if c.hprev is not None:
            n = (c.fim - c.ini).days + 1
            atual = c.hp.groupby("dia").size().reindex(idx, fill_value=0).cumsum().to_numpy()
            ant_idx = pd.date_range(c.ini - n * DIA, c.ini - DIA)
            anterior = c.hprev.groupby("dia").size().reindex(ant_idx, fill_value=0).cumsum().to_numpy()
            comp = pd.DataFrame({"dia do período": np.arange(1, n + 1), "Período atual": atual, "Período anterior": anterior})
            plot(px.line(comp, x="dia do período", y=["Período atual", "Período anterior"],
                         title="Treinos acumulados vs. período anterior",
                         color_discrete_sequence=[GREEN, GRAY]))
        else:
            ses = c.hp["periodo_dia"].value_counts().rename_axis("período").reset_index(name="treinos")
            plot(px.bar(ses, x="período", y="treinos", title="Treinos por período do dia"))
    with c2:
        tipos = pd.DataFrame({"golpe": ["Saque", "Forehand", "Backhand"],
                              "total": [c.hp["saques"].sum(), c.hp["forehands"].sum(), c.hp["backhands"].sum()]})
        plot(px.pie(tipos, names="golpe", values="total", hole=0.55, title="Mix de golpes"))

    c1, c2 = st.columns(2)
    with c1:
        piv = (c.hp.pivot_table(index="dow", columns="hora", values="sync_id", aggfunc="count")
               .reindex(index=range(7), columns=range(24)).fillna(0))
        piv.index = DOW
        plot(px.imshow(piv, aspect="auto", color_continuous_scale=SCALE_GREEN,
                       title="Quando treinam (dia da semana × hora)",
                       labels=dict(x="Hora do dia", y="", color="Treinos")), 330)
    with c2:
        dw = c.hp.groupby("dow").size().reindex(range(7), fill_value=0)
        dwdf = pd.DataFrame({"dia": DOW, "treinos": dw.to_numpy()})
        plot(px.bar(dwdf, x="dia", y="treinos", title="Treinos por dia da semana"), 330)

    c1, c2 = st.columns(2)
    with c1:
        pd_ = c.hp["periodo_dia"].value_counts().rename_axis("período").reset_index(name="treinos")
        plot(px.pie(pd_, names="período", values="treinos", hole=0.55, title="Período do dia"))
    with c2:
        plot(px.histogram(c.hp, x="minutos", nbins=25, title="Duração das sessões (min)",
                          labels={"minutos": "minutos"}))


def aba_engajamento(c):
    h, u, d = c.h, c.u, c.d
    dau, wau, mau = (D.ativos_janela(h, c.fim, n) for n in (1, 7, 30))
    dias30 = pd.date_range(c.fim - 29 * DIA, c.fim)
    media_dau = h.groupby("dia")["usuario_id"].nunique().reindex(dias30, fill_value=0).mean() if not h.empty else np.nan
    stick = media_dau / mau * 100 if mau else np.nan
    tem_criado = u["criado"].notna().any()
    mediana = np.nan
    if tem_criado and not h.empty:
        prim = h.groupby("usuario_id")["data"].min().rename("primeiro")
        t = u.merge(prim, left_on="id", right_index=True)
        mediana = ((t["primeiro"] - t["criado"]).dt.total_seconds() / 3600).clip(lower=0).median()
    volta = D.voltou_em(h, c.hoje)
    seq = D.maior_sequencia(h)
    inter = D.intervalo_entre_dias(h)
    kpi_row([
        ("DAU (último dia)", fmt(dau), None, "", "📆"),
        ("WAU (7 dias)", fmt(wau), None, "", "🗓️"),
        ("MAU (30 dias)", fmt(mau), None, "", "📅"),
        ("Stickiness", fmt(stick, 1, "%"), None, "DAU médio (30d) ÷ MAU.", "🧲"),
        ("Mediana até 1º treino", fmt(mediana, 1, " h"), None, "Do cadastro ao primeiro treino.", "🚦"),
    ])
    kpi_row([
        ("Voltou em 1 dia", fmt(volta[1], 0, "%"), None, "Treinou em outro dia até 1 dia após o 1º treino.", "↩️"),
        ("Voltou em 7 dias", fmt(volta[7], 0, "%"), None, "Treinou em outro dia até 7 dias após o 1º treino.", "↩️"),
        ("Voltou em 30 dias", fmt(volta[30], 0, "%"), None, "Treinou em outro dia até 30 dias após o 1º treino.", "↩️"),
        ("Maior sequência", fmt(seq.max() if len(seq) else np.nan, 0, " dias"), None, "Dias seguidos com treino (1 usuário).", "🔥"),
        ("Intervalo mediano", fmt(inter.median() if len(inter) else np.nan, 1, " dias"), None, "Entre dois dias de treino do mesmo usuário.", "⏳"),
    ])

    section("Ativação e crescimento")
    c1, c2 = st.columns(2)
    with c1:
        com_treino = len(set(h["usuario_id"]) & set(u["id"]))
        tres = int((h.groupby("usuario_id").size() >= 3).sum())
        conc = h.loc[h["concluido"], "usuario_id"].nunique()
        criou = d.loc[~d["excluido"], "usuario_id"].nunique()
        etapas = ["Cadastrados", "≥1 treino", "≥3 treinos", "≥1 treino concluído", "Criou desafio próprio"]
        fig = go.Figure(go.Funnel(y=etapas, x=[len(u), com_treino, tres, conc, criou],
                                  textinfo="value+percent initial", marker=dict(color=[GREEN, "#3FA877", "#6CBF93", LIME, YELLOW])))
        fig.update_layout(title="Funil de ativação (todos os cadastrados)")
        plot(fig, 360)
    with c2:
        nr = D.novos_vs_recorrentes(h).tail(16)
        if nr.empty:
            st.info("Sem dados.")
        else:
            nrd = nr.reset_index().melt(id_vars="semana", var_name="tipo", value_name="usuários")
            plot(px.bar(nrd, x="semana", y="usuários", color="tipo", barmode="stack",
                        title="Usuários ativos por semana: novos × recorrentes",
                        color_discrete_map={"Novos": YELLOW, "Recorrentes": GREEN}), 360)

    section("Intensidade de uso", "Quão frequente e quão concentrado é o uso no período")
    c1, c2 = st.columns(2)
    with c1:
        pr = D.pareto_usuarios(c.hp)
        if pr.empty:
            st.info("Sem treinos no período.")
        else:
            fig = go.Figure()
            fig.add_trace(go.Scatter(x=pr["pct_usuarios"], y=pr["pct_treinos"], name="Treinos acumulados",
                                     fill="tozeroy", line=dict(color=GREEN, width=3)))
            fig.add_trace(go.Scatter(x=[0, 100], y=[0, 100], name="Distribuição igual", line=dict(color=GRAY, dash="dash")))
            fig.update_layout(title=f"Concentração de treinos (10% mais ativos = {fmt(D.share_top(c.hp), 0, '%')})",
                              xaxis_title="% de usuários (do mais ao menos ativo)", yaxis_title="% acumulado de treinos")
            plot(fig)
    with c2:
        if c.hp.empty:
            st.info("Sem treinos no período.")
        else:
            por_user = c.hp.groupby("usuario_id").size().rename("treinos").reset_index()
            plot(px.histogram(por_user, x="treinos", nbins=20, title="Treinos por usuário (período)"))
    c1, c2 = st.columns(2)
    with c1:
        if c.hp.empty:
            st.info("Sem treinos no período.")
        else:
            dias_at = c.hp.groupby("usuario_id")["dia"].nunique().rename("dias").reset_index()
            plot(px.histogram(dias_at, x="dias", nbins=15, title="Dias distintos de treino por usuário (período)"))
    with c2:
        if h.empty:
            st.info("Sem dados.")
        else:
            wk = h.groupby("semana").agg(s=("sync_id", "count"), a=("usuario_id", "nunique")).reset_index()
            wk["sessões por usuário ativo"] = wk["s"] / wk["a"]
            plot(px.line(wk.tail(26), x="semana", y="sessões por usuário ativo", markers=True,
                         title="Sessões por usuário ativo, por semana"))

    section("Retenção por coorte", "Cada célula = % da coorte com ≥1 treino na semana indicada")
    ret = D.cohort_retention(u, h, c.hoje)
    if ret.empty:
        st.info("Retenção precisa da data de cadastro dos usuários.")
    else:
        fig = px.imshow(ret, text_auto=".0f", aspect="auto", color_continuous_scale=SCALE_GREEN,
                        labels=dict(x="Semanas desde o cadastro", y="Coorte (semana de cadastro)", color="% ativos"))
        plot(fig, max(300, 38 * len(ret) + 120))
        st.caption("Treinos feitos offline antes do cadastro contam na semana 0.")


def aba_desempenho(c):
    hp = c.hp
    if hp.empty:
        st.info("Nenhum treino no período.")
        return
    mel = D.melhoria_usuarios(c.h)
    kpi_row([
        ("Usuários que melhoraram", fmt((mel["delta"] > 0).mean() * 100 if len(mel) else np.nan, 0, "%"), None,
         "Precisão da 2ª metade das sessões > 1ª metade (usuários com ≥4 sessões).", "📈"),
        ("Melhoria média", fmt(mel["delta"].mean() if len(mel) else np.nan, 1, " p.p."), None,
         "Variação média de precisão (todas as sessões).", "🎯"),
        ("Pontuação mediana", fmt(hp["pontuacao"].median(), 0), None, "", "🏆"),
        ("Duração mediana", fmt(hp["minutos"].median(), 1, " min"), None, "", "⏱️"),
        ("Golpes recebidos / sessão", fmt(hp["golpes_recebidos"].mean(), 1), None, "", "🛡️"),
    ])

    col = "dia" if (c.fim - c.ini).days <= 31 else "semana"
    st.caption(f"Séries agregadas por {'dia' if col == 'dia' else 'semana'}.")
    g = hp.groupby(col).agg(precisao=("precisao", "mean"), pontuacao=("pontuacao", "mean"),
                            vel_media=("vel_media", "mean"), vel_max=("vel_max", "max"),
                            saques=("saques", "sum"), forehands=("forehands", "sum"),
                            backhands=("backhands", "sum")).reset_index()
    g["tendência"] = D.media_movel(g["precisao"], 3)

    section("Evolução")
    c1, c2 = st.columns(2)
    with c1:
        fig = go.Figure()
        fig.add_trace(go.Scatter(x=g[col], y=g["precisao"], name="Precisão", mode="lines+markers", line=dict(color=GREEN, width=2)))
        fig.add_trace(go.Scatter(x=g[col], y=g["tendência"], name="Tendência (média móvel 3)", line=dict(color=YELLOW, width=3, dash="dot")))
        fig.update_layout(title="Precisão média (%)")
        plot(fig)
    with c2:
        plot(px.line(g, x=col, y=["vel_media", "vel_max"], markers=True, title="Velocidade da bola (km/h)",
                     color_discrete_sequence=[BLUE, CLAY]))
    c1, c2 = st.columns(2)
    with c1:
        plot(px.bar(g, x=col, y=["saques", "forehands", "backhands"], title="Golpes por tipo",
                    color_discrete_sequence=[YELLOW, GREEN, BLUE]))
    with c2:
        plot(
    px.bar(
        g,
        x=col,
        y=["saques", "forehands", "backhands"],
        title="Proporção entre golpes (%)",
        color_discrete_sequence=[YELLOW, GREEN, BLUE]
    ).update_layout(barnorm="percent")
)


    section("Aprendizado", "Os usuários melhoram com a prática?")
    c1, c2 = st.columns(2)
    with c1:
        ca = D.curva_aprendizado(c.h)
        if ca.empty:
            st.info("Poucos dados para a curva de aprendizado.")
        else:
            fig = go.Figure()
            fig.add_trace(go.Bar(x=ca["n"], y=ca["usuarios"], name="Usuários", marker_color="#D6EBDD", yaxis="y2"))
            fig.add_trace(go.Scatter(x=ca["n"], y=ca["precisao"], name="Precisão média (%)", mode="lines+markers", line=dict(color=GREEN, width=3)))
            fig.update_layout(title="Curva de aprendizado: precisão × nº da sessão (15 = 15 ou mais)",
                              xaxis_title="nº da sessão do usuário",
                              yaxis2=dict(overlaying="y", side="right", showgrid=False, title="usuários"))
            plot(fig)
    with c2:
        if mel.empty:
            st.info("Precisa de usuários com ≥4 sessões.")
        else:
            fig = px.histogram(mel, x="delta", nbins=20, title="Melhoria de precisão por usuário (2ª metade − 1ª metade, p.p.)",
                               labels={"delta": "variação em pontos percentuais"})
            fig.add_vline(x=0, line_dash="dash", line_color=GRAY)
            plot(fig)

    section("Relações entre métricas")
    c1, c2 = st.columns(2)
    with c1:
        sc = hp.dropna(subset=["precisao"])
        plot(px.scatter(sc, x="minutos", y="pontuacao", color="precisao", color_continuous_scale=SCALE_GREEN,
                        title="Duração × pontuação (cor = precisão)", opacity=.8,
                        labels={"minutos": "duração (min)", "pontuacao": "pontuação"}))
    with c2:
        nomes = c.d.set_index("id")["nome"].to_dict()
        tt = hp.assign(treino=hp["treino_id"].map(lambda x: nomes.get(x, x)))
        top = tt["treino"].value_counts().head(8).index
        tt = tt[tt["treino"].isin(top)].dropna(subset=["precisao"])
        plot(px.box(tt, x="treino", y="precisao", title="Precisão por treino (8 mais executados)"))
    c1, c2 = st.columns(2)
    with c1:
        cm = D.correlacao(hp)
        plot(px.imshow(cm, text_auto=".2f", zmin=-1, zmax=1, color_continuous_scale=SCALE_DIV,
                       title="Correlação entre métricas das sessões"), 380)
    with c2:
        plot(px.histogram(hp, x="pontuacao", nbins=30, title="Distribuição de pontuação"), 380)

    section("Desempenho por treino")
    nomes = c.d.set_index("id")["nome"].to_dict()
    t = hp.assign(treino=hp["treino_id"].map(lambda x: nomes.get(x, x)))
    tab = t.groupby("treino").agg(execucoes=("sync_id", "count"), usuarios=("usuario_id", "nunique"),
                                  conclusao=("concluido", "mean"), minutos_medios=("minutos", "mean"),
                                  precisao=("precisao", "mean"), pontuacao=("pontuacao", "mean"),
                                  bolas=("bolas_detectadas", "sum"), acima_meta=("bolas_acima_meta", "sum"))
    tab["conclusao"] *= 100
    tab["% bolas acima da meta"] = tab["acima_meta"] / tab["bolas"].where(tab["bolas"] > 0) * 100
    tab = tab.drop(columns=["bolas", "acima_meta"]).sort_values("execucoes", ascending=False).round(1)
    st.dataframe(tab, column_config={
        "conclusao": st.column_config.ProgressColumn("conclusão %", min_value=0, max_value=100, format="%.0f"),
        "precisao": st.column_config.NumberColumn("precisão %"),
        "minutos_medios": st.column_config.NumberColumn("min. médios")})


def aba_treinos(c):
    h, hp, d = c.h, c.hp, c.d
    ativos = d[~d["excluido"]]
    usados = set(h["treino_id"])
    nunca = ativos[~ativos["id"].isin(usados)]
    pers = (D.tipo_treino(hp, d) == "Personalizado").mean() * 100 if len(hp) else np.nan
    kpi_row([
        ("Desafios ativos", fmt(len(ativos)), None, "Desafios personalizados não excluídos.", "🧩"),
        ("Excluídos", fmt(int(d["excluido"].sum())), None, "", "🗑️"),
        ("Criadores", fmt(ativos["usuario_id"].nunique()), None, "", "🛠️"),
        ("Execuções personalizadas", fmt(pers, 0, "%"), None, "% das execuções do período em desafios criados por usuários.", "🎛️"),
        ("Nunca executados", fmt(len(nunca) / max(len(ativos), 1) * 100, 0, "%"), None, "% dos desafios ativos sem nenhuma execução.", "💤"),
    ])
    section("Uso dos treinos no período")
    c1, c2 = st.columns(2)
    nomes = d.set_index("id")["nome"].to_dict()
    with c1:
        if hp.empty:
            st.info("Sem treinos no período.")
        else:
            tp = D.tipo_treino(hp, d).value_counts().rename_axis("tipo").reset_index(name="execuções")
            plot(px.pie(tp, names="tipo", values="execuções", hole=0.55, title="Treinos padrão × personalizados",
                        color_discrete_sequence=[GREEN, YELLOW]))
    with c2:
        if not hp.empty:
            top = hp["treino_id"].map(lambda x: nomes.get(x, x)).value_counts().head(10).rename_axis("treino").reset_index(name="execuções")
            plot(px.bar(top.iloc[::-1], x="execuções", y="treino", orientation="h", title="Top 10 treinos mais executados"))

    section("Desafios personalizados")
    if d.empty:
        st.info("Nenhum desafio personalizado criado ainda.")
        return
    c1, c2 = st.columns(2)
    with c1:
        hm = ativos.assign(categoria=ativos["categoria"].fillna("—"), dificuldade=ativos["dificuldade"].fillna("—"))
        piv = hm.pivot_table(index="categoria", columns="dificuldade", values="id", aggfunc="count").fillna(0)
        plot(px.imshow(piv, text_auto=True, aspect="auto", color_continuous_scale=SCALE_GREEN,
                       title="Categoria × dificuldade"), 340)
    with c2:
        cat = ativos["categoria"].fillna("—").value_counts().rename_axis("categoria").reset_index(name="desafios")
        plot(px.bar(cat, x="categoria", y="desafios", title="Desafios por categoria"), 340)
    c1, c2 = st.columns(2)
    with c1:
        s = ativos.dropna(subset=["criado"]).groupby(ativos["criado"].dt.to_period("W-SUN").dt.start_time).size()
        sd = s.rename("criados").reset_index().rename(columns={"criado": "semana"})
        plot(px.bar(sd, x="semana", y="criados", title="Desafios criados por semana"))
    with c2:
        plot(px.histogram(ativos, x="duracao_minutos", nbins=15, title="Duração planejada (min)"))
    c1, c2 = st.columns(2)
    with c1:
        plot(px.histogram(ativos[ativos["velocidade_alvo_kmh"] > 0], x="velocidade_alvo_kmh", nbins=12,
                          title="Velocidade-alvo definida nos desafios (km/h)"))
    with c2:
        primeira = h.groupby("treino_id")["data"].min().rename("primeira_exec")
        t = ativos.merge(primeira, left_on="id", right_index=True)
        t["dias_ate_exec"] = (t["primeira_exec"] - t["criado"]).dt.total_seconds() / 86400
        t = t[t["dias_ate_exec"].notna()]
        t["dias_ate_exec"] = t["dias_ate_exec"].clip(lower=0)
        if t.empty:
            st.info("Sem execuções de desafios personalizados.")
        else:
            plot(px.histogram(t, x="dias_ate_exec", nbins=15, title="Dias entre criar e executar pela 1ª vez",
                              labels={"dias_ate_exec": "dias"}))
    st.subheader("Desafios nunca executados")
    st.dataframe(nunca[["nome", "categoria", "dificuldade", "duracao_minutos", "criado"]].sort_values("criado", ascending=False),
                 hide_index=True)


def aba_gamificacao(c):
    u = c.u
    if u["xp"].notna().sum() == 0:
        st.info("Nenhum dado de gamificação sincronizado ainda.")
        return
    kpi_row([
        ("XP médio", fmt(u["xp"].mean(), 0), None, "", "✨"),
        ("XP total", fmt(u["xp"].sum(), 0), None, "", "💎"),
        ("Maior combo", fmt(u["maior_combo"].max(), 0), None, "", "🔥"),
        ("Chefões derrotados", fmt(u["chefoes"].sum(), 0), None, "", "👹"),
        ("Recorde de velocidade", fmt(u["pr_vel"].max(), 0, " km/h"), None, "", "🚀"),
    ])
    c1, c2 = st.columns(2)
    with c1:
        plot(px.histogram(u.dropna(subset=["xp"]), x="xp", nbins=20, title="Distribuição de XP de carreira"))
    with c2:
        nv = pd.cut(u["xp"], [-1, 500, 1500, 3000, 6000, 12000, np.inf],
                    labels=["0–500", "500–1,5k", "1,5k–3k", "3k–6k", "6k–12k", "12k+"]).value_counts().sort_index()
        nvd = pd.DataFrame({"faixa de XP": nv.index.astype(str), "usuários": nv.to_numpy()})
        plot(px.bar(nvd, x="faixa de XP", y="usuários", title="Usuários por faixa de XP"))
    c1, c2 = st.columns(2)
    with c1:
        plot(px.scatter(u.dropna(subset=["xp", "sessoes"]), x="sessoes", y="xp", hover_name="nome",
                        title="XP × sessões (cada ponto = um usuário)", opacity=.8))
    with c2:
        top = u.sort_values("xp", ascending=False).head(10)[["nome", "xp"]].iloc[::-1]
        plot(px.bar(top, x="xp", y="nome", orientation="h", title="Top 10 em XP"))
    c1, c2 = st.columns(2)
    with c1:
        plot(px.histogram(u.dropna(subset=["maior_combo"]), x="maior_combo", nbins=15, title="Distribuição do maior combo"))
    with c2:
        plot(px.histogram(u.dropna(subset=["pr_vel"]), x="pr_vel", nbins=15, title="Distribuição do recorde de velocidade (km/h)"))
    st.subheader("Ranking")
    rk = u.sort_values("xp", ascending=False).head(15)[["nome", "xp", "sessoes", "maior_combo", "chefoes", "pr_vel", "pr_forca"]]
    st.dataframe(rk.rename(columns={"maior_combo": "maior combo", "pr_vel": "PR velocidade", "pr_forca": "PR força"}),
                 hide_index=True)


def aba_usuarios(c):
    h, u = c.h, c.u
    r = D.resumo_usuarios(u, h, c.hoje)
    em_risco = r[(r["status"] == "Em risco (8–30d)")]
    kpi_row([
        ("Ativos (≤7d)", fmt((r["status"] == "Ativo (≤7d)").sum()), None, "", "🟢"),
        ("Em risco (8–30d)", fmt(len(em_risco)), None, "Treinaram, mas sumiram há 8–30 dias.", "🟡"),
        ("Inativos (>30d)", fmt((r["status"] == "Inativo (>30d)").sum()), None, "", "🔴"),
        ("Nunca treinaram", fmt((r["status"] == "Nunca treinou").sum()), None, "Cadastrados sem nenhuma sessão.", "⚪"),
        ("Usuários recorrentes", fmt((r["dias_ativos"] >= 2).mean() * 100 if len(r) else np.nan, 0, "%"), None,
         "% com treino em ≥2 dias diferentes.", "🔁"),
    ])
    c1, c2 = st.columns([1, 2])
    with c1:
        sc = r["status"].value_counts().rename_axis("status").reset_index(name="usuários")
        plot(px.pie(sc, names="status", values="usuários", hole=0.55, title="Status dos usuários",
                    color="status", color_discrete_map=STATUS_COLORS), 360)
    with c2:
        rf = r[r["treinos"] > 0].copy()
        if rf.empty:
            st.info("Sem usuários com treinos.")
        else:
            plot(px.scatter(rf, x="dias_sem_treinar", y="treinos", size="minutos", color="status", hover_name="nome",
                            color_discrete_map=STATUS_COLORS, size_max=40, opacity=.8,
                            title="Recência × frequência (tamanho = minutos treinados)",
                            labels={"dias_sem_treinar": "dias desde o último treino", "treinos": "total de treinos"}), 360)

    c1, c2 = st.columns(2)
    with c1:
        top = r[r["treinos"] > 0].sort_values("treinos", ascending=False).head(10)[["nome", "treinos"]].iloc[::-1]
        if not top.empty:
            plot(px.bar(top, x="treinos", y="nome", orientation="h", title="Top 10 usuários por nº de treinos"))
    with c2:
        if u["faixa"].notna().any():
            ft = r.dropna(subset=["faixa"]).groupby("faixa").agg(usuários=("id", "count"), treinos=("treinos", "sum"),
                                                                 precisao=("precisao", "mean")).reset_index()
            ft["treinos por usuário"] = ft["treinos"] / ft["usuários"]
            fig = go.Figure()
            fig.add_trace(go.Bar(x=ft["faixa"], y=ft["usuários"], name="Usuários", marker_color="#BFE3CD"))
            fig.add_trace(go.Scatter(x=ft["faixa"], y=ft["treinos por usuário"], name="Treinos por usuário",
                                     yaxis="y2", mode="lines+markers", line=dict(color=GREEN, width=3)))
            fig.update_layout(title="Faixa etária: usuários e engajamento",
                              yaxis2=dict(overlaying="y", side="right", showgrid=False))
            plot(fig)

    section("Usuários em risco", "Treinaram, mas não voltam há 8–30 dias — boas candidatas a uma notificação")
    risco = em_risco.copy()
    if not c.mostrar_pii:
        risco["email"] = risco["email"].map(D.mask_email)
    st.dataframe(risco.sort_values("treinos", ascending=False)[["nome", "email", "treinos", "ultima", "dias_sem_treinar"]].head(30),
                 hide_index=True)

    section("Todos os usuários")
    tabela = r[["id", "nome", "email", "criado", "ultima", "treinos", "dias_ativos", "minutos", "precisao", "xp", "status"]].copy()
    if not c.mostrar_pii:
        tabela["email"] = tabela["email"].map(D.mask_email)
    tabela = tabela.sort_values("ultima", ascending=False)
    st.dataframe(tabela.round({"minutos": 1, "precisao": 1, "xp": 0}), hide_index=True)
    st.download_button("⬇️ Baixar CSV", tabela.to_csv(index=False).encode("utf-8"), "usuarios_smartace.csv", "text/csv")

    section("Detalhe de um usuário")
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
                       "precisao", "bolas_detectadas", "bolas_acima_meta", "vel_max", "concluido"]].round(
                     {"minutos": 1, "precisao": 1, "vel_max": 1}),
                 hide_index=True)
    i = st.selectbox("Sessão para ver métricas da câmera", sess.index,
                     format_func=lambda k: f"{sess.loc[k, 'data']:%d/%m/%Y %H:%M} — {sess.loc[k, 'treino_id']}")
    if st.button("Carregar métricas da câmera"):
        row = sess.loc[i]
        raw = D.fetch_camera_json(config.FONTE, row["id"], row.get("metricas_camera_json"))
        df, err = D.camera_df(raw)
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


def aba_saude(c):
    h = c.h
    section("API")
    if config.FONTE == "demo":
        st.caption("Modo demonstração: sem backend real.")
    else:
        hl = D.api_health()
        skew = (hl["data"]["time"] / 1000 - time.time()) if hl["data"].get("time") else None
        kpi_row([
            ("Status", "online" if hl["ok"] else "offline", None, "", "🟢" if hl["ok"] else "🔴"),
            ("Latência", fmt(hl["ms"], 0, " ms"), None, "", "⚡"),
            ("Banco (segundo a API)", str(hl["data"].get("database", "—")), None, "", "🗄️"),
            ("Diferença de relógio", fmt(skew, 1, " s") if skew is not None else "—", None, "Servidor − este computador.", "🕒"),
        ])
        if hl.get("error"):
            st.error(hl["error"])
    if config.FONTE == "api":
        section("Volume de dados")
        try:
            cnt = D.api_counts()
        except D.ApiError as e:
            st.warning(str(e))
            cnt = None
    if config.FONTE == "api" and cnt:
        kpi_row([
            ("Usuários", fmt(cnt["usuario"]), None, "", "👥"),
            ("Sessões de treino", fmt(cnt["historicoTreino"]), None, "", "🎾"),
            ("Desafios", fmt(cnt["desafioPersonalizado"]), None, "", "🧩"),
            ("Tamanho médio do JSON da câmera", fmt(h["metricas_chars"].mean() / 1000, 1, " mil car."), None, "", "📦"),
        ])

    section("Sincronização", "O app grava offline e sincroniza depois")
    if h["recebido"].notna().any():
        lag = h["atraso_sync_h"].dropna()
        kpi_row([
            ("Último sync recebido", f"{h['recebido'].max():%d/%m %H:%M}", None, "", "📡"),
            ("Atraso mediano", fmt(lag.median(), 1, " h"), None, "Treino → servidor.", "⏱️"),
            ("Atraso p90", fmt(lag.quantile(0.9), 1, " h"), None, "", "🐢"),
            ("Sincronizados em >24h", fmt((lag > 24).mean() * 100, 1, "%"), None, "Uso offline prolongado.", "📴"),
        ])
        c1, c2 = st.columns(2)
        with c1:
            pdx = h.groupby(h["recebido"].dt.normalize()).size().rename("sessões recebidas").reset_index()
            plot(px.bar(pdx, x="recebido", y="sessões recebidas", title="Sessões recebidas por dia"), 300)
        with c2:
            lg = lag[(lag >= 0) & (lag <= 72)].rename("atraso (h)").to_frame()
            plot(px.histogram(lg, x="atraso (h)", nbins=36, title="Atraso de sincronização (até 72 h)"), 300)
        st.caption("Valores negativos indicam diferença de relógio entre o aparelho e o servidor.")
    else:
        st.caption("Sem dados de sincronização no modo demonstração.")

    section("Qualidade dos dados")
    q = D.qualidade(h, c.agora)
    st.dataframe(q, hide_index=True)
    comp = pd.DataFrame({
        "campo": ["Data de nascimento", "Gamificação sincronizada"],
        "% de usuários com o dado": [c.u["idade"].notna().mean() * 100, c.u["xp"].notna().mean() * 100]}).round(1)
    st.dataframe(comp, hide_index=True)


# --------------------------------------------------------------------------------------
# App
# --------------------------------------------------------------------------------------
def main():
    global _keys
    _keys = itertools.count()
    gate()
    st.markdown(CSS, unsafe_allow_html=True)
    agora = pd.Timestamp.now(tz=D.TZ).tz_localize(None)
    hoje = agora.normalize()

    with st.sidebar:
        st.markdown('<div class="brand">🎾 <b>Smart Ace</b><br><span>Analytics</span></div>', unsafe_allow_html=True)
        st.caption(f"Fonte: **{FONTE_NOME.get(config.FONTE, config.FONTE)}**")
        api_ok = None
        if config.FONTE != "demo":
            hl = D.api_health()
            api_ok = hl["ok"]
            st.caption(f"{'🟢' if api_ok else '🔴'} API {'online' if api_ok else 'offline'}"
                       + (f" · {hl['ms']:.0f} ms" if hl["ms"] else ""))

    data = carregar()
    u, h, d = data["u"], data["h"], data["d"]

    with st.sidebar:
        st.markdown("---")
        preset = st.selectbox("Período", ["Últimos 7 dias", "Últimos 30 dias", "Últimos 90 dias", "Tudo", "Personalizado"], index=1)
        if preset == "Personalizado":
            rng = st.date_input("Intervalo", value=(hoje - 29 * DIA, hoje))
            rng = rng if isinstance(rng, (tuple, list)) else (rng,)
            ini, fim = pd.Timestamp(rng[0]), pd.Timestamp(rng[-1])
        elif preset == "Tudo":
            ini = h["dia"].min() if not h.empty else hoje - 29 * DIA
            fim = hoje
        else:
            ini, fim = hoje - (int(preset.split()[1]) - 1) * DIA, hoje
        comparar = st.toggle("Comparar com período anterior", value=True) if preset != "Tudo" else False
        mostrar_pii = st.checkbox("Mostrar e-mails completos", value=False, help="Por padrão os e-mails ficam mascarados (LGPD).")
        if st.button("🔄 Atualizar dados"):
            st.cache_data.clear()
            st.rerun()

    hp = D.filtrar_periodo(h, ini, fim)
    hprev = None
    if comparar:
        n_dias = (fim - ini).days + 1
        hprev = D.filtrar_periodo(h, ini - n_dias * DIA, ini - DIA)

    carregado = time.strftime("%H:%M:%S", time.localtime(data["loaded_at"]))
    st.markdown(
        f'<div class="hero"><h1>🎾 Smart Ace · Analytics</h1>'
        f'<p>Monitoramento e análise de uso do aplicativo SmartTennis</p>'
        f'<span class="pill">📅 {ini:%d/%m/%Y} → {fim:%d/%m/%Y}</span>'
        f'<span class="pill">🗄️ {html.escape(FONTE_NOME.get(config.FONTE, config.FONTE))}</span>'
        f'<span class="pill">🔄 atualizado às {carregado}</span>'
        f'<span class="pill">👥 {len(u)} usuários · 🎾 {len(h)} sessões no total</span></div>',
        unsafe_allow_html=True)

    ctx = SimpleNamespace(u=u, h=h, d=d, hp=hp, hprev=hprev, ini=ini, fim=fim, hoje=hoje, agora=agora,
                          mostrar_pii=mostrar_pii)
    abas = st.tabs(["📊 Visão geral", "🔁 Engajamento", "🎯 Desempenho", "🧩 Treinos e desafios",
                    "🏆 Gamificação", "👥 Usuários", "🩺 Saúde do backend"])
    with abas[0]:
        aba_visao_geral(ctx)
    with abas[1]:
        aba_engajamento(ctx)
    with abas[2]:
        aba_desempenho(ctx)
    with abas[3]:
        aba_treinos(ctx)
    with abas[4]:
        aba_gamificacao(ctx)
    with abas[5]:
        aba_usuarios(ctx)
    with abas[6]:
        aba_saude(ctx)


main()
