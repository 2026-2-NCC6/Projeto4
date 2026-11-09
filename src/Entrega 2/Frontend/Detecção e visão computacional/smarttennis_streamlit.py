#!/usr/bin/env python3
"""Interface Streamlit do SmartTennis: análise -> planilha -> vídeo anotado.

Sem visualização em tempo real e sem velocidade: o vídeo é analisado de uma vez (só uma
barra de progresso), os dados vão para a planilha .xlsx e, DEPOIS da análise, você decide
gerar o vídeo anotado (lido a partir da planilha).

Mantenha este arquivo no mesmo diretório que smarttennis_tracker.py.
Para abrir: streamlit run smarttennis_streamlit.py
"""
from __future__ import annotations

import tempfile
from pathlib import Path

import streamlit as st

import smarttennis_tracker as tk

st.set_page_config(page_title="SmartTennis | Impactos", page_icon="🎾", layout="wide")

OUT_DIR = Path(__file__).with_name("saidas")
GRIDS = {"3 colunas × 2 linhas (6)": (3, 2), "2 colunas × 3 linhas (6)": (2, 3)}
WIDTHS = [320, 480, 640, 800, 960, 1280, 1600, 1920]
IMPACT_MODES = {"Só na parede (ignora quique no chão)": "parede", "Todos os impactos": "todos",
                "Só quiques no chão": "chao"}

ss = st.session_state
for key, value in {"file_id": None, "video_path": None, "video_name": None, "result": None,
                   "xlsx_path": None, "mp4_path": None, "codec": None, "info": None}.items():
    ss.setdefault(key, value)

st.title("🎾 SmartTennis — onde a bola bateu")
st.caption("Detecção clássica (OpenCV) + refino opcional por ML nas lacunas. A tela é dividida em quadrantes e cada "
           "impacto da bola é registrado na planilha; o vídeo anotado é gerado só depois da análise.")

uploaded = st.file_uploader("Vídeo de treino", type=["mp4", "mov", "avi", "mkv", "m4v"])
if uploaded is None:
    st.info("Envie um vídeo para começar. Pacotes necessários: `python -m pip install -r requirements.txt`.")
    st.stop()

if ss.file_id != uploaded.file_id:           # novo arquivo: grava em disco uma única vez
    suffix = Path(uploaded.name).suffix or ".mp4"
    with tempfile.NamedTemporaryFile(prefix="smarttennis_in_", suffix=suffix, delete=False) as tmp:
        tmp.write(uploaded.getbuffer())
    ss.update(file_id=uploaded.file_id, video_path=tmp.name, video_name=uploaded.name,
              result=None, xlsx_path=None, mp4_path=None, codec=None)
    try:
        ss.info = tk.probe_video(tmp.name)
    except ValueError as exc:
        ss.info = None
        st.error(str(exc))
        st.stop()

info = ss.info
st.caption(f"**{ss.video_name}** · {info.width}×{info.height} · {info.fps:.2f} FPS · "
           f"{info.frames:,} quadros")

with st.sidebar:
    st.header("Parâmetros")
    grid_name = st.selectbox("Quadrantes", list(GRIDS), index=0)
    area_text = st.text_input("Área dos quadrantes (opcional)", value="",
                              placeholder="0.10,0.05,0.90,0.70",
                              help="x0,y0,x1,y1 normalizados (0 a 1). Vazio = tela toda. Impactos fora da área "
                                   "são anotados como 'fora da área' (útil p/ ignorar a raquetada do jogador).")
    max_width = st.select_slider("Largura de processamento (menor = mais rápido)", options=WIDTHS, value=960)
    impact_name = st.selectbox("Impactos a contar", list(IMPACT_MODES), index=0,
                               help="Quique no chão = a bola desce na imagem e volta subindo, sem inverter na horizontal. "
                                    "Se a sua câmera for diferente, use 'Todos' e filtre pela coluna 'tipo'.")
    impact_angle = st.slider("Ângulo mínimo de impacto (graus)", 15, 90, 35,
                             help="Menor = detecta quiques mais rasos, mas aceita mais falsos.")
    max_predict = st.slider("Previsão quando perde a bola (quadros)", 0, 30, 12,
                            help="Por quantos quadros a bola perdida continua sendo prevista (trajetória com gravidade). "
                                 "0 = não prever; o rastro some quando a bola some.")
    with st.expander("Detecção da bola (avançado)"):
        c1, c2 = st.columns(2)
        h_low = c1.slider("H mín.", 0, 179, 20)
        s_low = c1.slider("S mín.", 0, 255, 65)
        v_low = c1.slider("V mín.", 0, 255, 55)
        h_high = c2.slider("H máx.", 0, 179, 48)
        s_high = c2.slider("S máx.", 0, 255, 255)
        v_high = c2.slider("V máx.", 0, 255, 255)
        min_area = st.number_input("Área mínima (px² em 960 px)", 1.0, 10000.0, 4.0, 1.0)
        max_area = st.number_input("Área máxima (px² em 960 px)", 1.0, 50000.0, 900.0, 10.0)
        min_circ = st.slider("Circularidade mínima", 0.0, 1.0, 0.18, 0.01)
        max_jump = st.slider("Salto máx. entre quadros (px em 960 px)", 10.0, 700.0, 180.0, 10.0)
        max_gap = st.slider("Quadros sem detecção tolerados", 0, 20, 6)
        min_speed = st.slider("Velocidade mínima da bola em jogo (px/quadro em 960 px)", 0.0, 30.0, 6.0, 0.5,
                              help="Trechos mais lentos que isso (bola rolando/parada) são ignorados.")
        use_motion = st.checkbox("Exigir movimento (ignora objetos amarelos parados)", value=True)
        roi_text = st.text_input("ROI onde procurar a bola (opcional)", value="",
                                 placeholder="0.18,0.12;0.90,0.18;0.98,0.92;0.08,0.95")
    with st.expander("Refino por ML (preenche lacunas da detecção clássica)"):
        ml_on = st.checkbox("Ligar refino por ML", value=True,
                            help="Depois da detecção clássica, um modelo treinado com a própria trajetória do vídeo "
                                 "procura a bola só nas lacunas. Só adiciona pontos; não remove detecções clássicas.")
        ml_mode = st.selectbox("Modelo", ["auto", "tracknet", "gbm"], index=0,
                               help="auto = TrackNet leve se o PyTorch estiver instalado, senão GBM (scikit-learn).")
        ml_width = st.select_slider("Largura vista pelo ML (maior = mais preciso e mais lento)",
                                    options=[640, 960, 1280, 1600, 1920], value=1280)
        ml_weights = st.text_input("Arquivo do modelo (opcional)", value="",
                                   placeholder="modelos/bola",
                                   help="Se existir, é carregado (sem treinar); senão treina e salva. Cria .pt ou .gbm.joblib.")
    render_width = st.select_slider("Largura do vídeo anotado", options=[640, 960, 1280, 1920], value=960)

cols, rows = GRIDS[grid_name]
cfg = tk.Config(hsv_low=(h_low, s_low, v_low), hsv_high=(h_high, s_high, v_high), min_area=float(min_area),
                max_area=float(max_area), min_circularity=float(min_circ), max_width=int(max_width),
                roi=roi_text.strip(), use_motion=use_motion, max_jump=float(max_jump), max_gap=int(max_gap),
                grid_cols=cols, grid_rows=rows, area=area_text.strip(), impact_angle=float(impact_angle),
                impact_mode=IMPACT_MODES[impact_name], max_predict=int(max_predict), min_speed=float(min_speed),
                max_bridge=max(int(max_gap), 20),
                ml=ml_mode if ml_on else "off", ml_width=int(ml_width), ml_weights=ml_weights.strip())

# ---------------------------------------------------------------- 1) análise
if st.button("1) Analisar vídeo e gerar planilha", type="primary"):
    try:
        bar = st.progress(0.0, text="Analisando…")

        def on_progress(done: int, total: int) -> None:
            bar.progress(min(1.0, done / max(1, total)), text=f"Analisando… quadro {done:,} de {total:,}")

        status = st.empty()
        result = tk.analyze(ss.video_path, cfg, on_progress, log=lambda m: status.caption(m))
        status.empty()
        OUT_DIR.mkdir(exist_ok=True)
        xlsx_path = OUT_DIR / f"{Path(ss.video_name).stem}_tracking.xlsx"
        tk.write_xlsx(result, xlsx_path)
        bar.empty()
        ss.update(result=result, xlsx_path=str(xlsx_path), mp4_path=None, codec=None)
    except ValueError as exc:
        st.error(str(exc))

result = ss.result
if result is None:
    st.stop()

# ---------------------------------------------------------------- resultados
n_frames = max(1, len(result.pos))
m1, m2, m3 = st.columns(3)
m1.metric("Quadros analisados", f"{len(result.pos):,}")
m2.metric("Quadros com bola", f"{result.n_with_ball / n_frames * 100:.1f}%",
          help=f"{result.n_observed:,} detectados, {result.n_ml:,} achados pelo refino ML, "
               f"{result.n_with_ball - result.n_observed - result.n_ml - result.n_predicted:,} "
               f"interpolados e {result.n_predicted:,} previstos (bola perdida)")
m3.metric("Impactos", len(result.impacts))

st.subheader("Quadrantes")
counts = result.counts
st.caption("Mapa como aparece na tela. ✅ = a bola bateu nesse quadrante.")
for r in range(rows):
    grid_cols = st.columns(cols)
    for c in range(cols):
        q = r * cols + c + 1
        grid_cols[c].metric(f"Q{q} · {tk.quadrant_label(q, cols, rows)}",
                            f"{'✅ ' if counts[q] else ''}{counts[q]}")
outside = sum(1 for i in result.impacts if i.quadrant is None)
if outside:
    st.caption(f"{outside} impacto(s) fora da área dos quadrantes.")
if result.ignored:
    st.caption(f"{len(result.ignored)} evento(s) ignorado(s) pelo modo escolhido (veja a aba **Ignorados** da planilha).")

if result.impacts:
    st.subheader("Impactos")
    st.dataframe([{"n": i.n, "quadro": i.frame, "tempo (s)": round(i.time_s, 2), "x": i.x, "y": i.y,
                   "quadrante": f"Q{i.quadrant}" if i.quadrant else "fora da área",
                   "ângulo": i.angle, "tipo": i.kind} for i in result.impacts], hide_index=True)
else:
    st.warning("Nenhum impacto detectado. Tente reduzir o ângulo mínimo ou ajustar a cor/ROI da bola.")

with open(ss.xlsx_path, "rb") as f:
    st.download_button("⬇ Baixar planilha (.xlsx)", f.read(), file_name=Path(ss.xlsx_path).name,
                       mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
st.caption(f"Também salva em: `{ss.xlsx_path}`")

# ---------------------------------------------------------------- 2) vídeo (depois da análise)
st.divider()
st.caption("O vídeo é gerado a partir da planilha. Se apagar impactos falsos na aba **Impactos**, "
           "use o comando `--render-from` do tracker para refazer o vídeo.")
if st.button("2) Gerar vídeo anotado"):
    try:
        bar = st.progress(0.0, text="Gerando vídeo…")

        def on_render(done: int, total: int) -> None:
            bar.progress(min(1.0, done / max(1, total)), text=f"Gerando vídeo… quadro {done:,} de {total:,}")

        mp4_path = OUT_DIR / f"{Path(ss.video_name).stem}_tracking.mp4"
        data = tk.read_xlsx(ss.xlsx_path)
        ss.codec = tk.render_video(ss.video_path, data, mp4_path, render_width, on_render)
        ss.mp4_path = str(mp4_path)
        bar.empty()
    except (ValueError, RuntimeError) as exc:
        st.error(str(exc))

if ss.mp4_path and Path(ss.mp4_path).is_file():
    st.success(f"Vídeo anotado pronto (codec {ss.codec}).")
    with open(ss.mp4_path, "rb") as f:
        st.download_button("⬇ Baixar vídeo anotado (.mp4)", f.read(), file_name=Path(ss.mp4_path).name,
                           mime="video/mp4")
    st.caption(f"Também salvo em: `{ss.mp4_path}`")
