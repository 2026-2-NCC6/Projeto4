#!/usr/bin/env python3
"""SmartTennis: rastreamento clássico da bola (OpenCV, sem ML) + impacto por quadrante.

Sem velocidade e sem janela ao vivo. O fluxo tem três etapas independentes:

  1. ANÁLISE (rápida, nada é desenhado): em cada quadro acha candidatos a bola por
     cor HSV + movimento. Depois liga os candidatos de TODOS os quadros numa única
     trajetória (programação dinâmica), o que descarta falsos positivos isolados e
     preenche pequenas falhas. Por fim detecta os IMPACTOS (mudança brusca de direção,
     com a posição estimada no cruzamento das retas antes/depois) e diz em qual dos
     quadrantes da grade (padrão 3x2 = 6 quadrantes) cada impacto caiu.
  2. PLANILHA (.xlsx): Resumo, Impactos e Trajetoria (quadro a quadro).
  3. VÍDEO ANOTADO: gerado só DEPOIS, lendo a planilha (bola, rastro e impactos).
     Dá para editar a aba Impactos (apagar falsos) e gerar o vídeo de novo.

Exemplos:
  python smarttennis_tracker.py video.mp4
  python smarttennis_tracker.py video.mp4 --grid 2x3 --area 0.1,0.05,0.9,0.7
  python smarttennis_tracker.py video.mp4 --no-video
  python smarttennis_tracker.py video.mp4 --render-from video_tracking.xlsx
"""
from __future__ import annotations

import argparse
import math
import queue
import sys
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterator, Optional

import cv2
import numpy as np

REF_WIDTH = 960.0   # largura de referência: áreas/saltos em px são definidos nela e escalados
MAX_CANDS = 6       # candidatos guardados por quadro (os de maior pontuação)
ProgressFn = Optional[Callable[[int, int], None]]

ORIGINS = {1: "detectada", 0: "interpolada", 2: "prevista"}
ORIGIN_CODES = {v: k for k, v in ORIGINS.items()}

# rótulos da aba Resumo (o leitor da planilha procura por eles)
L_FILE, L_FPS, L_W, L_H = "Arquivo", "FPS", "Largura (px)", "Altura (px)"
L_FRAMES, L_OBS, L_ANY = "Quadros analisados", "Quadros com bola detectada", "Quadros com bola (inclui interpolados e previstos)"
L_PRED = "Quadros com bola prevista (perdida)"
L_COLS, L_ROWS, L_AREA = "Grade: colunas", "Grade: linhas", "Grade: área (x0,y0,x1,y1 normalizado)"


# --------------------------------------------------------------------------
# Configuração e utilitários
# --------------------------------------------------------------------------
@dataclass
class Config:
    hsv_low: tuple = (20, 65, 55)
    hsv_high: tuple = (48, 255, 255)
    min_area: float = 4.0          # px², medido em REF_WIDTH (escala sozinho com --max-width)
    max_area: float = 900.0
    min_circularity: float = 0.18
    max_width: int = 960           # largura de processamento (0 = original)
    roi: str = ""                  # polígono normalizado onde a bola é procurada
    use_motion: bool = True
    motion_thr: float = 18.0
    max_jump: float = 180.0        # px/quadro máximo entre detecções (em REF_WIDTH)
    max_gap: int = 6               # quadros sem detecção que ainda podem ser ligados
    min_track_len: int = 4         # detecções mínimas para aceitar um trecho de trajetória
    grid_cols: int = 3
    grid_rows: int = 2
    area: str = ""                 # região dividida em quadrantes (padrão: quadro inteiro)
    impact_angle: float = 35.0     # mudança mínima de direção (graus) para contar impacto
    min_impact_gap: int = 5        # quadros mínimos entre dois impactos
    impact_mode: str = "parede"    # parede = ignora quiques no chão | todos | chao = só quiques no chão
    max_predict: int = 12          # quadros que a bola perdida continua sendo prevista (trajetória balística)
    min_speed: float = 6.0         # velocidade mediana mínima (px/quadro em REF_WIDTH): ignora bola rolando/parada
    max_bridge: int = 20           # maior buraco (quadros) preenchido entre duas detecções coerentes

    def validate(self) -> None:
        if any(a > b for a, b in zip(self.hsv_low, self.hsv_high)):
            raise ValueError("Em cada componente HSV, o mínimo deve ser menor ou igual ao máximo.")
        if self.min_area > self.max_area:
            raise ValueError("A área mínima não pode ser maior que a máxima.")
        if not (1 <= self.grid_cols <= 6 and 1 <= self.grid_rows <= 6):
            raise ValueError("A grade deve ter de 1 a 6 colunas/linhas.")
        parse_area(self.area)
        if self.impact_mode not in ("parede", "todos", "chao"):
            raise ValueError("Modo de impacto inválido: use parede, todos ou chao.")


def parse_polygon(text: Optional[str], width: int, height: int) -> Optional[np.ndarray]:
    text = (text or "").strip()
    if not text:
        return None
    try:
        pts = []
        for item in text.split(";"):
            x, y = item.split(",")
            xn, yn = float(x), float(y)
            if not (0 <= xn <= 1 and 0 <= yn <= 1):
                raise ValueError
            pts.append((int(xn * width), int(yn * height)))
        if len(pts) < 3:
            raise ValueError
        return np.array(pts, dtype=np.int32)
    except Exception as exc:
        raise ValueError("ROI inválida. Exemplo: '0.1,0.1;0.9,0.1;0.95,0.9;0.05,0.9'") from exc


def parse_area(text: Optional[str]) -> tuple[float, float, float, float]:
    text = (text or "").strip()
    if not text:
        return (0.0, 0.0, 1.0, 1.0)
    try:
        x0, y0, x1, y1 = (float(v) for v in text.split(","))
        if not (0 <= x0 < x1 <= 1 and 0 <= y0 < y1 <= 1):
            raise ValueError
        return (x0, y0, x1, y1)
    except Exception as exc:
        raise ValueError("Área inválida. Exemplo: '0.1,0.05,0.9,0.7' (x0,y0,x1,y1 entre 0 e 1)") from exc


def parse_grid(text: str) -> tuple[int, int]:
    try:
        cols, rows = (int(v) for v in text.lower().replace("×", "x").replace(",", "x").split("x"))
        if not (1 <= cols <= 6 and 1 <= rows <= 6):
            raise ValueError
        return cols, rows
    except Exception as exc:
        raise ValueError("Grade inválida. Use COLUNASxLINHAS, por exemplo 3x2.") from exc


def quadrant_of(xn: float, yn: float, area, cols: int, rows: int) -> Optional[int]:
    """Quadrante 1..cols*rows (linha a linha, de cima para baixo) ou None se fora da área."""
    x0, y0, x1, y1 = area
    if not (x0 <= xn <= x1 and y0 <= yn <= y1):
        return None
    c = min(cols - 1, int((xn - x0) / (x1 - x0) * cols))
    r = min(rows - 1, int((yn - y0) / (y1 - y0) * rows))
    return r * cols + c + 1


_ROW_NAMES = {1: ("",), 2: ("superior", "inferior"), 3: ("superior", "meio", "inferior")}
_COL_NAMES = {1: ("",), 2: ("esquerdo", "direito"), 3: ("esquerdo", "central", "direito")}


def quadrant_label(q: int, cols: int, rows: int) -> str:
    r, c = divmod(q - 1, cols)
    if rows in _ROW_NAMES and cols in _COL_NAMES:
        text = " ".join(p for p in (_ROW_NAMES[rows][r], _COL_NAMES[cols][c]) if p)
        return text.capitalize() or "Área inteira"
    return f"Linha {r + 1}, coluna {c + 1}"


# --------------------------------------------------------------------------
# Leitura de vídeo (decodifica em outra thread, em paralelo ao processamento)
# --------------------------------------------------------------------------
@dataclass
class VideoInfo:
    fps: float
    width: int
    height: int
    frames: int


def probe_video(path) -> VideoInfo:
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        cap.release()
        raise ValueError("O OpenCV não conseguiu abrir esse vídeo (tente MP4/H.264, AVI ou MOV).")
    fps = float(cap.get(cv2.CAP_PROP_FPS))
    info = VideoInfo(fps if math.isfinite(fps) and fps > 0 else 30.0,
                     int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)), int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
                     max(0, int(cap.get(cv2.CAP_PROP_FRAME_COUNT))))
    cap.release()
    if info.width <= 0 or info.height <= 0:
        raise ValueError("O vídeo não informou uma resolução válida.")
    return info


def iter_frames(path, size: Optional[tuple[int, int]] = None, depth: int = 6) -> Iterator[np.ndarray]:
    """Gera os quadros (já redimensionados para `size`). Decodificar + redimensionar numa
    thread própria sobrepõe esse custo ao da análise (o OpenCV libera o GIL)."""
    q: queue.Queue = queue.Queue(maxsize=depth)
    stop = threading.Event()

    def put(item) -> bool:
        while not stop.is_set():
            try:
                q.put(item, timeout=0.2)
                return True
            except queue.Full:
                pass
        return False

    def worker() -> None:
        cap = cv2.VideoCapture(str(path))
        try:
            while not stop.is_set():
                ok, frame = cap.read()
                if not ok:
                    break
                if size is not None and (frame.shape[1], frame.shape[0]) != tuple(size):
                    frame = cv2.resize(frame, size, interpolation=cv2.INTER_AREA)
                if not put(frame):
                    return
        finally:
            cap.release()
            put(None)

    thread = threading.Thread(target=worker, daemon=True)
    thread.start()
    try:
        while True:
            item = q.get()
            if item is None:
                return
            yield item
    finally:
        stop.set()
        thread.join(timeout=2.0)


# --------------------------------------------------------------------------
# Detector: candidatos por quadro (cor HSV + movimento + forma)
# --------------------------------------------------------------------------
class BallDetector:
    """Devolve até MAX_CANDS candidatos (x, y, raio, pontuação) por quadro.

    Em modo de movimento só entram pixels que são da cor da bola E estão se movendo:
    objetos amarelos parados nem chegam a virar contorno (menos trabalho e menos erro).
    """

    def __init__(self, width: int, height: int, cfg: Config, polygon: Optional[np.ndarray]):
        self.scale = max(0.2, width / REF_WIDTH)
        s = self.scale
        mask = np.zeros((height, width), np.uint8)
        if polygon is None:
            mask[:] = 255
        else:
            cv2.fillPoly(mask, [polygon], 255)
        ys, xs = np.nonzero(mask)
        if len(xs) == 0:
            self.x0, self.y0, self.x1, self.y1 = 0, 0, width, height
            mask[:] = 255
        else:
            self.x0, self.x1 = int(xs.min()), int(xs.max()) + 1
            self.y0, self.y1 = int(ys.min()), int(ys.max()) + 1
        self.roi = np.ascontiguousarray(mask[self.y0:self.y1, self.x0:self.x1])
        self.roi_area = max(1, cv2.countNonZero(self.roi))
        self.roi_full = self.roi_area == self.roi.size

        lo, hi = cfg.hsv_low, cfg.hsv_high
        # faixa relaxada (bola borrada perde saturação/brilho); o movimento segura o ruído
        self.low = np.array([max(0, lo[0] - 3), min(lo[1], max(15, lo[1] - 40)),
                             min(lo[2], max(30, lo[2] - 25))], np.uint8)
        self.high = np.array([min(179, hi[0] + 3), hi[1], hi[2]], np.uint8)
        self.hue_center = (lo[0] + hi[0]) / 2.0
        self.hue_half = max(1.0, (hi[0] - lo[0]) / 2.0)
        self.s_low = float(lo[1])
        self.min_area = cfg.min_area * s * s
        self.area_limit = min(cfg.max_area * s * s, self.roi_area * 0.02)
        self.min_circ = float(cfg.min_circularity)
        self.use_motion = bool(cfg.use_motion)
        self.motion_thr = float(cfg.motion_thr)

        ks = max(3, int(round(9 * s))) | 1
        self.k_motion = cv2.getStructuringElement(cv2.MORPH_RECT, (ks, ks))
        self.k_close = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
        self.prev_gray: Optional[np.ndarray] = None
        self.shake_frac = 0.01     # acima disso o quadro passa pela compensação de câmera
        self._hann = None
        # sem filtro de movimento: mapa de calor para ignorar objetos amarelos parados
        self.cell = max(8, int(round(16 * s)))
        self.heat = np.zeros((self.roi.shape[0] // self.cell + 2, self.roi.shape[1] // self.cell + 2), np.float32)

    def _global_shift(self, prev_gray: np.ndarray, gray: np.ndarray):
        """Tremor/pan de câmera (dx, dy) em px vs. quadro anterior; None se não confiável.
        Só é chamada quando o quadro mostra sinais de movimento global (ver detect)."""
        prev = cv2.resize(prev_gray, None, fx=0.25, fy=0.25, interpolation=cv2.INTER_AREA).astype(np.float32)
        small = cv2.resize(gray, None, fx=0.25, fy=0.25, interpolation=cv2.INTER_AREA).astype(np.float32)
        if min(small.shape) < 16:
            return None
        if self._hann is None or self._hann.shape != small.shape:
            self._hann = cv2.createHanningWindow((small.shape[1], small.shape[0]), cv2.CV_32F)
        (dx, dy), _ = cv2.phaseCorrelate(prev, small, self._hann)
        if abs(dx) > 8 or abs(dy) > 8 or (abs(dx) < 0.1 and abs(dy) < 0.1):
            return None
        h_, w_ = small.shape
        best_err, best = float(cv2.mean(cv2.absdiff(small, prev))[0]) * 0.85, None
        for sgn in (1.0, -1.0):    # o sinal da correlação de fase varia: testa os dois
            moved = cv2.warpAffine(prev, np.float32([[1, 0, sgn * dx], [0, 1, sgn * dy]]), (w_, h_),
                                   flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
            err = float(cv2.mean(cv2.absdiff(small, moved))[0])
            if err < best_err:
                best_err, best = err, (sgn * dx * 4.0, sgn * dy * 4.0)
        return best

    def _motion(self, gray: np.ndarray, prev: np.ndarray):
        diff = cv2.absdiff(gray, prev)
        thr = float(np.clip(4.0 * cv2.mean(diff)[0], self.motion_thr, 60.0))   # sobe com ruído global
        motion = cv2.threshold(diff, thr, 255, cv2.THRESH_BINARY)[1]
        if not self.roi_full:
            motion = cv2.bitwise_and(motion, self.roi)
        return motion, cv2.countNonZero(motion) / self.roi_area

    def detect(self, frame: np.ndarray):
        """Retorna (candidatos, estado). estado: ok | first | unreliable | cut."""
        crop = frame[self.y0:self.y1, self.x0:self.x1]
        ch, cw = crop.shape[:2]
        gate = None
        if self.use_motion:
            gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
            prev, self.prev_gray = self.prev_gray, gray
            if prev is None or prev.shape != gray.shape:
                return [], "first"
            motion, frac = self._motion(gray, prev)
            if frac > self.shake_frac:
                # muito movimento: pode ser tremor/pan de câmera -> compensa e recalcula
                shift = self._global_shift(prev, gray)
                if shift is not None and (abs(shift[0]) > 0.3 or abs(shift[1]) > 0.3):
                    prev = cv2.warpAffine(prev, np.float32([[1, 0, shift[0]], [0, 1, shift[1]]]), (cw, ch),
                                          flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
                    motion, frac = self._motion(gray, prev)
            if frac > 0.5:
                return [], "cut"           # troca de cena / mudança brusca de luz
            if frac > 0.25:
                return [], "unreliable"    # câmera balançando: movimento não é confiável
            if frac == 0.0:
                return [], "ok"
            gate = cv2.dilate(motion, self.k_motion)

        hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
        mask = cv2.inRange(hsv, self.low, self.high)
        if gate is not None:
            mask = cv2.bitwise_and(mask, gate)
        elif not self.roi_full:
            mask = cv2.bitwise_and(mask, self.roi)
        if not cv2.countNonZero(mask):
            return [], "ok"
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, self.k_close)

        out: list[tuple[float, float, float, float]] = []
        hit_cells = []
        if gate is None:
            self.heat *= 0.97
        for c in cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[-2]:
            area = cv2.contourArea(c)
            if area < self.min_area or area > self.area_limit:
                continue
            per = cv2.arcLength(c, True)
            if per <= 0:
                continue
            circ = min(1.0, 4.0 * math.pi * area / (per * per))
            x, y, w, h = cv2.boundingRect(c)
            (_, _), (rw, rh), _ = cv2.minAreaRect(c)
            elong = max(rw, rh) / max(min(rw, rh), 1e-3)
            fill = area / max(rw * rh, 1e-3)
            if elong < 1.8:                        # círculo
                if circ < self.min_circ:
                    continue
                shape = circ
            elif elong <= 6.0 and fill >= 0.5 and (gate is not None or circ >= self.min_circ):
                shape = 0.8 * min(1.0, fill)       # rastro alongado por motion blur
            else:
                continue
            m = cv2.moments(c)
            cx, cy = (m["m10"] / m["m00"], m["m01"] / m["m00"]) if m["m00"] > 0 else (x + w / 2.0, y + h / 2.0)
            mh, ms, _, _ = cv2.mean(hsv[y:y + h, x:x + w], mask[y:y + h, x:x + w])
            hue_term = 1.0 - min(1.0, abs(mh - self.hue_center) / (self.hue_half * 1.2))
            sat_term = float(np.clip((ms - self.s_low) / max(1.0, 255.0 - self.s_low) * 2.0, 0.0, 1.0))
            score = 0.45 * shape + 0.55 * (0.6 * hue_term + 0.4 * sat_term)
            if gate is None:                       # sem movimento: derruba o que fica sempre no mesmo lugar
                gy, gx = int(cy // self.cell), int(cx // self.cell)
                hit_cells.append((gy, gx))
                if self.heat[max(0, gy - 1):gy + 2, max(0, gx - 1):gx + 2].max() > 10.0:
                    score *= 0.3
            out.append((cx + self.x0, cy + self.y0, math.sqrt(area / math.pi), score))
        for gy, gx in hit_cells:
            self.heat[gy, gx] += 1.0
        out.sort(key=lambda c: -c[3])
        return out[:MAX_CANDS], "ok"


# --------------------------------------------------------------------------
# Ligação global dos candidatos (programação dinâmica) -> trajetória da bola
# --------------------------------------------------------------------------
def link_trajectory(cands: list, cut_cum: list, scale: float, max_jump: float,
                    max_gap: int, min_len: int, min_speed: float = 0.0) -> list[list[tuple[int, float, float]]]:
    """Acha os trechos de trajetória de maior pontuação em todo o vídeo.

    Cada candidato é um nó; o melhor caminho até ele soma a "recompensa" (pontuação do
    candidato + bônus por ser rápido) menos custos de transição (desvio da posição prevista
    por velocidade E aceleração, parado, buraco, mudança de tamanho). Caminhos ruins não
    valem a pena e recomeçam. Depois extrai os trechos em ordem de pontuação, sem repetir
    quadros. Retorna listas de (quadro, x, y), uma por trecho, da maior pontuação à menor.
    """
    C_FIRST, C_DEV, C_STILL, C_GAP, C_SIZE = 0.6, 2.5, 0.8, 0.15, 0.3
    T, X, Y, R, S, by_frame = [], [], [], [], [], []
    for t, frame_c in enumerate(cands):
        ids = []
        for x, y, r, s in frame_c:
            ids.append(len(T))
            T.append(t); X.append(x); Y.append(y); R.append(max(r, 0.5)); S.append(s)
        by_frame.append(ids)
    n = len(T)
    best = [0.0] * n
    prev = [-1] * n
    vx: list[Optional[float]] = [None] * n
    vy: list[Optional[float]] = [None] * n
    ay = [0.0] * n
    lcost = [0.0] * n                    # custo do elo escolhido (alto = quique ou salto entre objetos)
    fresh = [False] * n                  # elo B: o caminho de q recomeça em prev[q], ignorando o passado dele
    min_step, v_fast, a_max = 1.5 * scale, 8.0 * scale, 2.5 * scale
    hypot, log = math.hypot, math.log

    for q in range(n):
        t, reward = T[q], 0.5 + S[q]
        bv, bp, bvx, bvy, bay, blc, bfresh = reward, -1, None, None, 0.0, 0.0, False
        for g in range(1, max_gap + 2):
            t1 = t - g
            if t1 < 0 or cut_cum[t] - cut_cum[t1] > 0:
                break
            lim = max_jump * g
            for p in by_frame[t1]:
                dx, dy = X[q] - X[p], Y[q] - Y[p]
                d = hypot(dx, dy)
                if d > lim:
                    continue
                extra = C_GAP * (g - 1) + C_SIZE * min(1.0, abs(log(R[q] / R[p])) / 0.7)
                if d < min_step * g:
                    extra += C_STILL
                bonus_speed = 0.5 * min(1.0, d / g / v_fast)
                sx, sy = dx / g, dy / g
                # opção A: continua o caminho de p (velocidade e aceleração herdadas)
                # opção B: p como início de um caminho novo (só vale se A for ruim: p pode ter
                # herdado velocidade de um falso positivo, e isso não pode impedir a bola de ser ligada)
                for fresh_opt in (False, True):
                    if fresh_opt:
                        if vx[p] is None:
                            break                        # p já é início de caminho: B == A
                        cost, consist, base = C_FIRST * min(1.0, d / lim) + extra, 0.3, 0.5 + S[p]
                    elif vx[p] is None:
                        cost, consist, base = C_FIRST * min(1.0, d / lim) + extra, 0.3, best[p]
                    else:
                        ex = X[p] + vx[p] * g - X[q]
                        ey = Y[p] + vy[p] * g + 0.5 * ay[p] * g * g - Y[q]
                        tol = (3.0 + 2.0 * (g - 1)) * scale + 0.15 * hypot(vx[p], vy[p]) * g
                        dev = min(1.0, hypot(ex, ey) / (3.0 * tol))
                        cost, consist, base = C_DEV * dev + extra, 1.0 - dev, best[p]
                    # bola em jogo é rápida: objetos lentos (bola parada/rolando) rendem menos
                    gain = reward + consist * bonus_speed - cost
                    if gain <= 0.0:                      # elo ruim por si só: nenhum histórico acumulado o salva
                        continue
                    val = base + gain
                    if val > bv:
                        bv, bp, blc, bfresh = val, p, cost, (fresh_opt and vx[p] is not None)
                        if vx[p] is None or fresh_opt:
                            bvx, bvy, bay = sx, sy, 0.0
                        else:                            # suaviza velocidade e estima a aceleração (gravidade)
                            bvx, bvy = 0.7 * sx + 0.3 * vx[p], 0.7 * sy + 0.3 * vy[p]
                            acc = (sy - vy[p]) / ((g + 1) * 0.5)
                            bay = max(-a_max, min(a_max, 0.5 * ay[p] + 0.5 * acc))
        best[q], prev[q], vx[q], vy[q], ay[q], lcost[q], fresh[q] = bv, bp, bvx, bvy, bay, blc, bfresh

    used = bytearray(n)
    occupied = bytearray(len(cands))
    chains = []
    for i in sorted(range(n), key=best.__getitem__, reverse=True):
        if best[i] < 0.5 * min_len:
            break
        if used[i] or occupied[T[i]]:
            continue
        chain, j = [], i
        while j != -1 and not used[j] and not occupied[T[j]]:
            chain.append(j)
            if lcost[j] > 1.25:          # elo fraco (quique ou salto para outro objeto): o trecho acaba aqui
                break
            if fresh[j]:                 # o caminho recomeça no predecessor, sem o passado dele
                k = prev[j]
                if k != -1 and not used[k] and not occupied[T[k]]:
                    chain.append(k)
                break
            j = prev[j]
        if len(chain) < min_len:
            continue
        chain.reverse()
        xs, ys = [X[j] for j in chain], [Y[j] for j in chain]
        if max(max(xs) - min(xs), max(ys) - min(ys)) < 6.0 * scale:   # não saiu do lugar
            continue
        steps = sorted(math.hypot(X[b] - X[a], Y[b] - Y[a]) / (T[b] - T[a]) for a, b in zip(chain, chain[1:]))
        if steps[len(steps) // 2] < min_speed:                         # lento demais p/ ser a bola em jogo
            for j in chain:
                used[j] = 1
            continue
        for j in chain:
            used[j] = 1
            occupied[T[j]] = 1
        chains.append([(T[j], X[j], Y[j]) for j in chain])
    return chains


class _Motion:
    """Movimento local ajustado a poucos pontos: x linear; y parabólico se houver pontos
    suficientes (aceleração limitada à gravidade plausível), senão linear."""
    __slots__ = ("tm", "xm", "ym", "vx", "vy", "ay", "speed")

    def pos(self, t: float) -> tuple[float, float]:
        dt = t - self.tm
        return self.xm + self.vx * dt, self.ym + self.vy * dt + 0.5 * self.ay * dt * dt


def _motion_fit(pts, scale: float, grav: float = 0.0) -> _Motion:
    """Ajuste local. Com poucos pontos usa a aceleração `grav` (estimada do próprio vídeo),
    que é o que torna a previsão em buracos longos realista (a bola cai)."""
    m = _Motion()
    if len(pts) == 1:                      # sem histórico: assume parada
        m.tm, m.xm, m.ym = pts[0]
        m.vx = m.vy = m.ay = m.speed = 0.0
        return m
    tm0 = sum(p[0] for p in pts) / len(pts)
    m.tm, m.xm, m.ym, m.vx, m.vy, _ = _fit_line([(t, x, y - 0.5 * grav * (t - tm0) ** 2) for t, x, y in pts])
    m.ay = grav
    if len(pts) >= 5:
        c2, c1, c0 = np.polyfit([p[0] - m.tm for p in pts], [p[2] for p in pts], 2)
        if abs(2.0 * c2) <= 2.5 * scale:
            m.ay, m.vy, m.ym = float(2.0 * c2), float(c1), float(c0)
        else:
            m.tm, m.xm, m.ym, m.vx, m.vy, _ = _fit_line([(t, x, y - 0.5 * grav * (t - tm0) ** 2) for t, x, y in pts])
            m.ay = grav
    m.speed = math.hypot(m.vx, m.vy)
    return m


def estimate_gravity(chains, scale: float) -> float:
    """Aceleração vertical típica (px/quadro²) medida nos trechos longos do próprio vídeo."""
    vals = []
    for c in chains:
        if len(c) >= 8:
            c2 = np.polyfit([p[0] for p in c], [p[2] for p in c], 2)[0]
            if abs(2.0 * c2) <= 2.5 * scale:
                vals.append(2.0 * float(c2))
    return float(np.median(vals)) if len(vals) >= 2 else 0.0


def _bridge_geometry(left, right, scale: float, grav: float = 0.0):
    """Compara a previsão balística para frente (final de `left`) com a para trás (início de
    `right`). Retorna (fa, fb, tc, distância mínima, tolerância): tc é o instante em que as
    duas previsões mais se aproximam (cruzam), o que também cobre um quique/impacto no meio."""
    ta, tb = left[-1][0], right[0][0]
    fa, fb = _motion_fit(left, scale, grav), _motion_fit(right, scale, grav)
    best_t, best_d = float(ta), float("inf")
    steps = max(2, int((tb - ta) * 2))
    for k in range(steps + 1):
        t = ta + (tb - ta) * k / steps
        (x1, y1), (x2, y2) = fa.pos(t), fb.pos(t)
        d = math.hypot(x1 - x2, y1 - y2)
        if d < best_d:
            best_t, best_d = t, d
    g = tb - ta
    tol = (5.0 + g) * scale + 0.1 * 0.5 * (fa.speed + fb.speed) * g
    return fa, fb, best_t, best_d, tol


def clean_chain(chain, scale: float, min_len: int):
    """Remove pontos fora da curva (não batem com a previsão de nenhum dos dois lados) e
    descarta trechos erráticos (ziguezague), que não são uma bola em voo."""
    pts = list(chain)
    while len(pts) >= min_len:
        worst, worst_err = -1, 0.0
        for i in range(len(pts)):
            errs = []
            for side in (pts[max(0, i - 3):i], pts[i + 1:i + 4]):
                if len(side) >= 2:
                    f = _fit_line(side)
                    ex, ey = f[1] + f[3] * (pts[i][0] - f[0]) - pts[i][1], f[2] + f[4] * (pts[i][0] - f[0]) - pts[i][2]
                    errs.append((math.hypot(ex, ey), math.hypot(f[3], f[4])))
            if len(errs) < (1 if i in (0, len(pts) - 1) else 2):   # pontas só têm um lado para comparar
                continue
            err = min(e[0] for e in errs)           # um quique concorda com um dos lados; ruído com nenhum
            dt_nb = (pts[min(i + 1, len(pts) - 1)][0] - pts[max(i - 1, 0)][0]) / (2.0 if 0 < i < len(pts) - 1 else 1.0)
            tol = 4.0 * scale + 0.15 * max(e[1] for e in errs) * dt_nb
            if err > tol and err - tol > worst_err:
                worst, worst_err = i, err - tol
        if worst < 0:
            break
        del pts[worst]
    if len(pts) < min_len or (len(pts) < 8 and pts[-1][0] - pts[0][0] + 1 > 1.8 * len(pts)):
        return None                       # curto e esburacado: provavelmente pontos soltos coincidentes
    turns = steps = 0
    for (t0, x0, y0), (t1, x1, y1), (t2, x2, y2) in zip(pts, pts[1:], pts[2:]):
        ax, ay_, bx, by = (x1 - x0) / (t1 - t0), (y1 - y0) / (t1 - t0), (x2 - x1) / (t2 - t1), (y2 - y1) / (t2 - t1)
        na, nb = math.hypot(ax, ay_), math.hypot(bx, by)
        if na < 2.0 * scale or nb < 2.0 * scale:
            continue
        steps += 1
        if (ax * bx + ay_ * by) / (na * nb) < -0.17:   # virou mais de ~100 graus de um quadro para o outro
            turns += 1
    if steps >= 3 and turns / steps > 0.25:
        return None
    return pts


def select_track(chains, scale: float, max_bridge: int, grav: float = 0.0):
    """Uma bola só: aceita os trechos do melhor para o pior, mas só se forem coerentes no
    tempo com os trechos vizinhos já aceitos (as previsões balísticas dos dois lados se
    encontram). Isso impede que outro objeto amarelo 'roube' a trajetória quando a bola some."""
    accepted: list = []
    for c in chains:
        t0, t1 = c[0][0], c[-1][0]
        if any(t0 <= a[-1][0] and t1 >= a[0][0] for a in accepted):
            continue                                     # cai dentro de um trecho já aceito
        before = max((a for a in accepted if a[-1][0] < t0), key=lambda a: a[-1][0], default=None)
        after = min((a for a in accepted if a[0][0] > t1), key=lambda a: a[0][0], default=None)
        ok = True
        for left, right in ((before, c), (c, after)):
            if left is None or right is None or right[0][0] - left[-1][0] > max_bridge:
                continue
            fa, fb, _, dist, tol = _bridge_geometry(left[-4:], right[:4], scale, grav)
            g = right[0][0] - left[-1][0]
            near = math.hypot(right[0][1] - left[-1][1], right[0][2] - left[-1][2])
            # compatível se as previsões se cruzam, ou se um trecho começa colado no fim do outro
            # (impactos seguidos: o ângulo entre os dois não passa pelo teste de cruzamento)
            trusted = fa.speed if left is not c else fb.speed      # velocidade do trecho já aceito
            if dist > tol and not (g <= 3 and near <= 1.5 * trusted * g + 8.0 * scale):
                ok = False
                break
        if ok or len(c) >= 10:                 # trecho longo e rápido já se sustenta sozinho
            accepted.append(c)
    return sorted(p for a in accepted for p in a)


def build_positions(obs, n_frames: int, scale: float, max_gap: int, max_bridge: int,
                    max_predict: int, width: int, height: int, grav: float = 0.0) -> list:
    """Posição por quadro: detectada (1), interpolada entre duas detecções coerentes (0) ou
    prevista por trajetória balística depois que a bola some (2). Nunca 'inventa' linhas
    aleatórias: sem detecção coerente, só prevê por `max_predict` quadros e para."""
    pos: list = [None] * n_frames
    for t, x, y in obs:
        pos[t] = (x, y, 1)
    n = len(obs)

    def window_before(i: int):
        j = i
        while j > 0 and i - j < 4 and obs[j][0] - obs[j - 1][0] <= max_bridge and obs[i][0] - obs[j - 1][0] <= 12:
            j -= 1
        return obs[j:i + 1]

    def window_after(i: int):
        j = i
        while j < n - 1 and j - i < 4 and obs[j + 1][0] - obs[j][0] <= max_bridge and obs[j + 1][0] - obs[i][0] <= 12:
            j += 1
        return obs[i:j + 1]

    def forward_predict(i: int, last_t: int) -> None:
        ta, xa, ya = obs[i]
        if max_predict <= 0 or len(window_before(i)) < 2:
            return
        f = _motion_fit(window_before(i), scale, grav)
        ox, oy = xa - f.pos(ta)[0], ya - f.pos(ta)[1]
        for t in range(ta + 1, min(last_t, ta + max_predict) + 1):
            x, y = f.pos(t)
            x, y = x + ox, y + oy
            if not (-0.02 * width <= x <= 1.02 * width and -0.02 * height <= y <= 1.02 * height):
                break
            pos[t] = (x, y, 2)

    for i in range(n - 1):
        ta, xa, ya = obs[i]
        tb, xb, yb = obs[i + 1]
        g = tb - ta
        if g <= 1:
            continue
        done = False
        if g <= max_bridge:
            fa, fb, tc, dist, tol = _bridge_geometry(window_before(i), window_after(i + 1), scale, grav)
            if dist <= tol:
                pa, pb = fa.pos(ta), fb.pos(tb)
                oax, oay, obx, oby = xa - pa[0], ya - pa[1], xb - pb[0], yb - pb[1]
                fx, fy = fa.pos(tc)
                gx, gy = fb.pos(tc)
                for t in range(ta + 1, tb):
                    if t <= tc:                      # segue a previsão vinda de trás até o cruzamento...
                        w = (tc - t) / max(tc - ta, 1e-6)
                        pos[t] = (fa.pos(t)[0] + oax * w, fa.pos(t)[1] + oay * w, 0)
                    else:                            # ...e depois a previsão vinda da frente (cobre quique)
                        w = (t - tc) / max(tb - tc, 1e-6)
                        px_, py_ = fb.pos(t)
                        pos[t] = (px_ + obx * w + (fx - gx) * (1 - w), py_ + oby * w + (fy - gy) * (1 - w), 0)
                done = True
            elif g <= max_gap + 1:                   # ligado pela DP dentro do trecho: interpola reto
                for t in range(ta + 1, tb):
                    a = (t - ta) / g
                    pos[t] = (xa + a * (xb - xa), ya + a * (yb - ya), 0)
                done = True
        if not done:
            forward_predict(i, tb - 1)
    if n:
        forward_predict(n - 1, n_frames - 1)
    return pos


# --------------------------------------------------------------------------
# Impactos
# --------------------------------------------------------------------------
def _fit_line(pts):
    """Reta (posição em função do tempo) por mínimos quadrados: (t_med, x_med, y_med, vx, vy, rms)."""
    n = len(pts)
    tm = sum(p[0] for p in pts) / n
    xm = sum(p[1] for p in pts) / n
    ym = sum(p[2] for p in pts) / n
    den = sum((p[0] - tm) ** 2 for p in pts)
    vx = sum((p[0] - tm) * (p[1] - xm) for p in pts) / den
    vy = sum((p[0] - tm) * (p[2] - ym) for p in pts) / den
    rms = math.sqrt(sum((p[1] - xm - vx * (p[0] - tm)) ** 2 + (p[2] - ym - vy * (p[0] - tm)) ** 2
                        for p in pts) / n)
    return tm, xm, ym, vx, vy, rms


def detect_impacts(obs: list[tuple[int, float, float]], scale: float, max_gap: int,
                   angle_thr: float, min_sep: int) -> list[dict]:
    """Impacto = ponto onde a trajetória muda bruscamente de direção.

    Para cada par de quadros consecutivos ajusta uma reta aos pontos de antes e outra aos
    de depois; o impacto é onde as duas retas se encontram (posição sub-quadro, bem mais
    precisa que o ponto detectado mais próximo). Exige ângulo mínimo, velocidade mínima,
    retas bem ajustadas e que elas realmente se cruzem.
    """
    W, span = 4, 10
    min_speed, min_comp = 3.0 * scale, 2.5 * scale
    found = []
    n = len(obs)
    for k in range(1, n - 2):
        ta, tb = obs[k][0], obs[k + 1][0]
        if tb - ta > max_gap + 1:
            continue
        lo = k
        while lo > 0 and k - lo + 1 < W and obs[lo][0] - obs[lo - 1][0] <= max_gap + 1 and ta - obs[lo - 1][0] <= span:
            lo -= 1
        hi = k + 1
        while hi < n - 1 and hi - k < W and obs[hi + 1][0] - obs[hi][0] <= max_gap + 1 and obs[hi + 1][0] - tb <= span:
            hi += 1
        before, after = obs[lo:k + 1], obs[k + 1:hi + 1]
        if len(before) < 2 or len(after) < 2:
            continue
        fi, fo = _fit_line(before), _fit_line(after)
        vxi, vyi, vxo, vyo = fi[3], fi[4], fo[3], fo[4]
        si, so = math.hypot(vxi, vyi), math.hypot(vxo, vyo)
        if si < min_speed or so < min_speed or fi[5] > 4.0 * scale or fo[5] > 4.0 * scale:
            continue
        angle = math.degrees(math.acos(max(-1.0, min(1.0, (vxi * vxo + vyi * vyo) / (si * so)))))
        flip_v = vyi * vyo < 0 and min(abs(vyi), abs(vyo)) >= min_comp
        flip_h = vxi * vxo < 0 and min(abs(vxi), abs(vxo)) >= min_comp
        if angle < (angle_thr * 0.6 if (flip_v or flip_h) else angle_thr):
            continue
        # instante em que as duas retas ficam mais próximas (limitado ao intervalo ta..tb)
        tref = (ta + tb) / 2.0
        dax = fi[1] + vxi * (tref - fi[0]) - (fo[1] + vxo * (tref - fo[0]))
        day = fi[2] + vyi * (tref - fi[0]) - (fo[2] + vyo * (tref - fo[0]))
        dvx, dvy = vxi - vxo, vyi - vyo
        dv2 = dvx * dvx + dvy * dvy
        ts = min(max(tref - (dax * dvx + day * dvy) / dv2 if dv2 > 1e-9 else tref, ta), tb)
        xi, yi = fi[1] + vxi * (ts - fi[0]), fi[2] + vyi * (ts - fi[0])
        xo, yo = fo[1] + vxo * (ts - fo[0]), fo[2] + vyo * (ts - fo[0])
        meet = math.hypot(xi - xo, yi - yo)
        if meet > 8.0 * scale:                     # retas não se cruzam: não é um quique
            continue
        # quique no chão: a bola vem descendo na imagem e sai subindo, sem inverter na horizontal
        floor = vyi > 0 and vyo < 0 and min(abs(vyi), abs(vyo)) >= min_comp and not flip_h
        kind = ("quique no chão" if floor else "inversão vertical" if flip_v
                else "inversão horizontal" if flip_h else "mudança de direção")
        quality = angle - 6.0 * meet / scale - 3.0 * (fi[5] + fo[5]) / scale
        found.append({"t": ts, "x": (xi + xo) / 2.0, "y": (yi + yo) / 2.0,
                      "angle": angle, "kind": kind, "quality": quality, "floor": floor})
    found.sort(key=lambda d: d["t"])
    picked: list[dict] = []
    for is_floor in (False, True):                  # vizinhança tratada por classe: um quique no chão
        last = None                                 # colado na parede não apaga o impacto da parede
        for d in (f for f in found if f["floor"] == is_floor):
            if last is not None and d["t"] - last["t"] < min_sep:
                if d["quality"] > last["quality"]:
                    picked[picked.index(last)] = d
                    last = d
            else:
                picked.append(d)
                last = d
    picked.sort(key=lambda d: d["t"])
    return picked


# --------------------------------------------------------------------------
# Resultado, análise completa
# --------------------------------------------------------------------------
@dataclass
class Impact:
    n: int
    frame: int
    time_s: float
    x: float                    # px do vídeo original
    y: float
    quadrant: Optional[int]     # None = fora da área da grade
    angle: float
    kind: str


@dataclass
class Result:
    video_name: str
    fps: float
    width: int                  # resolução do vídeo original
    height: int
    cols: int
    rows: int
    area: tuple
    pos: list                   # por quadro: None ou (x, y, origem: 1 detectada, 0 interpolada, 2 prevista)
    impacts: list[Impact]
    params: dict = field(default_factory=dict)
    ignored: list[Impact] = field(default_factory=list)   # quiques no chão (ou paredes) fora do modo escolhido

    @property
    def counts(self) -> dict:
        counts = {q: 0 for q in range(1, self.cols * self.rows + 1)}
        for imp in self.impacts:
            if imp.quadrant:
                counts[imp.quadrant] += 1
        return counts

    @property
    def n_observed(self) -> int:
        return sum(1 for p in self.pos if p is not None and p[2] == 1)

    @property
    def n_with_ball(self) -> int:
        return sum(1 for p in self.pos if p is not None)

    @property
    def n_predicted(self) -> int:
        return sum(1 for p in self.pos if p is not None and p[2] == 2)


def analyze(video_path, cfg: Config, progress: ProgressFn = None) -> Result:
    cfg.validate()
    info = probe_video(video_path)
    pw = info.width
    if cfg.max_width > 0 and info.width > cfg.max_width:
        pw = cfg.max_width
    pw = max(2, pw)
    ph = max(2, int(round(info.height * pw / info.width)))
    area = parse_area(cfg.area)
    polygon = parse_polygon(cfg.roi, pw, ph)
    det = BallDetector(pw, ph, cfg, polygon)
    scale = det.scale
    total = info.frames

    cands: list = []
    cut_cum: list[int] = []
    cuts = 0
    for frame in iter_frames(video_path, (pw, ph)):
        found, state = det.detect(frame)
        if state == "cut":
            cuts += 1
        cands.append(found)
        cut_cum.append(cuts)
        if progress and len(cands) % 30 == 0:
            progress(len(cands), total)
    n_frames = len(cands)
    if progress:
        progress(n_frames, max(total, n_frames))

    chains = link_trajectory(cands, cut_cum, scale, cfg.max_jump * scale, cfg.max_gap, cfg.min_track_len,
                             cfg.min_speed * scale)
    chains = [c for c in (clean_chain(c, scale, cfg.min_track_len) for c in chains) if c]
    grav = estimate_gravity(chains, scale)
    obs = select_track(chains, scale, cfg.max_bridge, grav)

    kx, ky = info.width / pw, info.height / ph          # coordenadas de processamento -> originais
    pos_proc = build_positions(obs, n_frames, scale, cfg.max_gap, cfg.max_bridge, cfg.max_predict, pw, ph, grav)
    pos = [None if p is None else (round(p[0] * kx, 2), round(p[1] * ky, 2), p[2]) for p in pos_proc]

    def make_impact(n: int, d: dict) -> Impact:
        x, y = d["x"] * kx, d["y"] * ky
        return Impact(n, int(round(d["t"])), d["t"] / info.fps, round(x, 1), round(y, 1),
                      quadrant_of(x / info.width, y / info.height, area, cfg.grid_cols, cfg.grid_rows),
                      round(d["angle"], 1), d["kind"])

    kept, dropped = [], []
    for d in detect_impacts(obs, scale, cfg.max_gap, cfg.impact_angle, cfg.min_impact_gap):
        keep = cfg.impact_mode == "todos" or (d["floor"] == (cfg.impact_mode == "chao"))
        (kept if keep else dropped).append(d)
    impacts = [make_impact(i, d) for i, d in enumerate(kept, 1)]
    ignored = [make_impact(i, d) for i, d in enumerate(dropped, 1)]

    params = {
        "Cor HSV mínima": ",".join(map(str, cfg.hsv_low)), "Cor HSV máxima": ",".join(map(str, cfg.hsv_high)),
        "Área mín./máx. (px² em 960 px)": f"{cfg.min_area:g} / {cfg.max_area:g}",
        "Circularidade mínima": f"{cfg.min_circularity:g}", "Largura de processamento": f"{pw}",
        "Filtro de movimento": "ligado" if cfg.use_motion else "desligado", "ROI da bola": cfg.roi or "quadro inteiro",
        "Salto máx. (px/quadro em 960 px)": f"{cfg.max_jump:g}", "Falha máx. ligável (quadros)": f"{cfg.max_gap}",
        "Ângulo mínimo de impacto (graus)": f"{cfg.impact_angle:g}",
        "Intervalo mín. entre impactos (quadros)": f"{cfg.min_impact_gap}",
        "Impactos contados": {"parede": "parede (ignora quiques no chão)", "todos": "todos",
                              "chao": "só quiques no chão"}[cfg.impact_mode],
        "Velocidade mediana mínima (px/quadro em 960 px)": f"{cfg.min_speed:g}",
        "Predição após perder a bola (quadros)": f"{cfg.max_predict}", "Buraco máx. preenchido (quadros)": f"{cfg.max_bridge}",
    }
    return Result(Path(str(video_path)).name, info.fps, info.width, info.height, cfg.grid_cols, cfg.grid_rows,
                  area, pos, impacts, params, ignored)


# --------------------------------------------------------------------------
# Planilha (.xlsx)
# --------------------------------------------------------------------------
def write_xlsx(result: Result, path) -> None:
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Border, Font, PatternFill, Side

    bold, head_fill = Font(bold=True), PatternFill("solid", fgColor="DDEBF7")
    hit_fill = PatternFill("solid", fgColor="FFE699")
    thin = Side(style="thin", color="999999")
    box = Border(left=thin, right=thin, top=thin, bottom=thin)
    cols, rows, W, H = result.cols, result.rows, result.width, result.height
    counts = result.counts

    wb = Workbook()
    ws = wb.active
    ws.title = "Resumo"
    ws["A1"] = "SmartTennis — resultado da análise"
    ws["A1"].font = Font(bold=True, size=14)
    meta = [(L_FILE, result.video_name), (L_FPS, round(result.fps, 4)), (L_W, W), (L_H, H),
            (L_FRAMES, len(result.pos)), (L_OBS, result.n_observed), (L_ANY, result.n_with_ball),
            (L_PRED, result.n_predicted),
            (L_COLS, cols), (L_ROWS, rows), (L_AREA, ",".join(f"{v:g}" for v in result.area)),
            ("Impactos detectados", len(result.impacts)),
            ("Impactos fora da área da grade", sum(1 for i in result.impacts if i.quadrant is None))]
    r = 3
    for label, value in meta:
        ws.cell(r, 1, label).font = bold
        ws.cell(r, 2, value)
        r += 1

    r += 1
    ws.cell(r, 1, "Quadrantes (numerados da esquerda p/ direita, de cima p/ baixo)").font = bold
    r += 1
    for j, name in enumerate(("Quadrante", "Região", "Impactos", "Bateu?"), 1):
        c = ws.cell(r, j, name)
        c.font, c.fill, c.border = bold, head_fill, box
    for q in range(1, cols * rows + 1):
        r += 1
        for j, v in enumerate((f"Q{q}", quadrant_label(q, cols, rows), counts[q], "Sim" if counts[q] else "Não"), 1):
            ws.cell(r, j, v).border = box
    r += 2
    ws.cell(r, 1, "Mapa (como aparece na tela)").font = bold
    for q in range(1, cols * rows + 1):
        rr, cc = divmod(q - 1, cols)
        c = ws.cell(r + 1 + rr, 1 + cc, f"Q{q}: {counts[q]}")
        c.border, c.alignment = box, Alignment(horizontal="center")
        if counts[q]:
            c.fill = hit_fill
    r += rows + 3
    ws.cell(r, 1, "Parâmetros usados").font = bold
    for label, value in result.params.items():
        r += 1
        ws.cell(r, 1, label)
        ws.cell(r, 2, value)
    ws.column_dimensions["A"].width = 46
    ws.column_dimensions["B"].width = 26
    ws.column_dimensions["C"].width = 14
    ws.column_dimensions["D"].width = 12

    wi = wb.create_sheet("Impactos")
    wi.append(["n", "quadro", "tempo_s", "x_px", "y_px", "quadrante", "regiao", "angulo_graus", "tipo"])
    for imp in result.impacts:
        q = imp.quadrant
        wi.append([imp.n, imp.frame, round(imp.time_s, 3), imp.x, imp.y, q,
                   quadrant_label(q, cols, rows) if q else "fora da área", imp.angle, imp.kind])

    wt = wb.create_sheet("Trajetoria")
    wt.append(["quadro", "tempo_s", "x_px", "y_px", "origem", "quadrante"])
    for t, p in enumerate(result.pos):
        if p is None:
            wt.append([t, round(t / result.fps, 3)])
        else:
            wt.append([t, round(t / result.fps, 3), p[0], p[1], ORIGINS[p[2]],
                       quadrant_of(p[0] / W, p[1] / H, result.area, cols, rows)])
    sheets = [(wi, (6, 9, 10, 10, 10, 11, 20, 14, 22)), (wt, (9, 10, 10, 10, 11, 11))]
    if result.ignored:                              # fica registrado, mas não entra na contagem nem no vídeo
        wg = wb.create_sheet("Ignorados")
        wg.append(["n", "quadro", "tempo_s", "x_px", "y_px", "angulo_graus", "tipo"])
        for imp in result.ignored:
            wg.append([imp.n, imp.frame, round(imp.time_s, 3), imp.x, imp.y, imp.angle, imp.kind])
        sheets.append((wg, (6, 9, 10, 10, 10, 14, 22)))
    for sheet, widths in sheets:
        for j, w in enumerate(widths):
            sheet.column_dimensions[chr(65 + j)].width = w
        sheet.freeze_panes = "A2"
        for c in sheet[1]:
            c.font, c.fill = bold, head_fill
    wb.save(str(path))


def read_xlsx(path) -> Result:
    from openpyxl import load_workbook
    wb = load_workbook(str(path), read_only=True, data_only=True)
    try:
        meta = {}
        for row in wb["Resumo"].iter_rows(values_only=True):
            if row and isinstance(row[0], str) and len(row) > 1 and row[1] is not None:
                meta.setdefault(row[0].strip(), row[1])
        try:
            fps, W, H = float(meta[L_FPS]), int(meta[L_W]), int(meta[L_H])
            cols, rows = int(meta[L_COLS]), int(meta[L_ROWS])
            area = parse_area(str(meta[L_AREA]))
        except (KeyError, ValueError) as exc:
            raise ValueError("Planilha inválida: a aba Resumo não tem os dados do vídeo/grade.") from exc
        pos: list = []
        for row in wb["Trajetoria"].iter_rows(min_row=2, values_only=True):
            if row is None or row[0] is None:
                continue
            t = int(row[0])
            while len(pos) <= t:
                pos.append(None)
            if row[2] is not None and row[3] is not None:
                code = ORIGIN_CODES.get(str(row[4]).strip().lower(), None) if isinstance(row[4], str) else int(row[4] or 0)
                pos[t] = (float(row[2]), float(row[3]), 0 if code is None else code)
        impacts = []
        for row in wb["Impactos"].iter_rows(min_row=2, values_only=True):
            if row is None or row[1] is None or row[3] is None or row[4] is None:
                continue
            x, y = float(row[3]), float(row[4])
            q = int(row[5]) if isinstance(row[5], (int, float)) else quadrant_of(x / W, y / H, area, cols, rows)
            impacts.append(Impact(len(impacts) + 1, int(row[1]), float(row[2] or 0.0), x, y, q,
                                  float(row[7] or 0.0), str(row[8] or "")))
    finally:
        wb.close()
    return Result(str(meta.get(L_FILE, "")), fps, W, H, cols, rows, area, pos, impacts)


# --------------------------------------------------------------------------
# Vídeo anotado (lê a planilha; roda depois da análise)
# --------------------------------------------------------------------------
def open_writer(path, fps: float, size: tuple[int, int]):
    for code in ("avc1", "mp4v"):      # avc1 (H.264) toca no navegador; mp4v é o plano B
        writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*code), fps, size)
        if writer.isOpened():
            return writer, code
        writer.release()
    raise RuntimeError("Não foi possível criar o vídeo de saída (nenhum codec disponível).")


def render_video(video_path, result: Result, out_path, render_width: int = 960,
                 progress: ProgressFn = None, trail_len: int = 20, hold_s: float = 1.5) -> str:
    """Desenha grade, bola, rastro e impactos. Retorna o codec usado."""
    info = probe_video(video_path)
    if render_width and 0 < render_width < info.width:
        out_w = int(render_width)
        out_h = int(round(info.height * out_w / info.width))
    else:
        out_w, out_h = info.width, info.height
    out_w -= out_w % 2
    out_h -= out_h % 2
    kx, ky = out_w / result.width, out_h / result.height
    sc = max(0.5, out_w / 960.0)
    font, fs, th = cv2.FONT_HERSHEY_SIMPLEX, 0.55 * sc, max(1, int(round(sc)))

    n_pos = len(result.pos)
    px = np.full(n_pos, np.nan, np.float32)
    py = np.full(n_pos, np.nan, np.float32)
    po = np.zeros(n_pos, np.uint8)
    for t, p in enumerate(result.pos):
        if p is not None:
            px[t], py[t], po[t] = p[0] * kx, p[1] * ky, p[2]
    events = sorted(((i.frame, int(round(i.x * kx)), int(round(i.y * ky)), i.quadrant) for i in result.impacts))

    cols, rows = result.cols, result.rows
    ax0, ay0, ax1, ay1 = result.area
    gx0, gy0, gx1, gy1 = int(ax0 * out_w), int(ay0 * out_h), int(ax1 * out_w), int(ay1 * out_h)
    xs = [gx0 + (gx1 - gx0) * c // cols for c in range(cols + 1)]
    ys = [gy0 + (gy1 - gy0) * r // rows for r in range(rows + 1)]
    hold = max(1, int(round(hold_s * result.fps)))
    jump_lim = 0.25 * out_w                                  # salto máx. entre quadros no rastro
    counts = {q: 0 for q in range(1, cols * rows + 1)}

    writer, codec = open_writer(out_path, result.fps, (out_w, out_h))
    past: list = []
    ni = 0
    try:
        for idx, frame in enumerate(iter_frames(video_path, (out_w, out_h))):
            while ni < len(events) and events[ni][0] <= idx:
                past.append(events[ni])
                if events[ni][3]:
                    counts[events[ni][3]] += 1
                ni += 1
            active = [e for e in past[-6:] if idx - e[0] <= hold]

            for e in active:                                  # quadrante do impacto, esmaecendo
                if e[3]:
                    r_, c_ = divmod(e[3] - 1, cols)
                    sub = frame[ys[r_]:ys[r_ + 1], xs[c_]:xs[c_ + 1]]
                    a = 0.45 * (1.0 - (idx - e[0]) / hold)
                    frame[ys[r_]:ys[r_ + 1], xs[c_]:xs[c_ + 1]] = cv2.addWeighted(
                        np.full(sub.shape, (0, 0, 255), np.uint8), a, sub, 1.0 - a, 0)
            for xv in xs:
                cv2.line(frame, (xv, gy0), (xv, gy1), (255, 255, 255), 1)
            for yv in ys:
                cv2.line(frame, (gx0, yv), (gx1, yv), (255, 255, 255), 1)
            for q in range(1, cols * rows + 1):
                r_, c_ = divmod(q - 1, cols)
                cv2.putText(frame, f"Q{q}", (xs[c_] + 6, ys[r_] + int(20 * sc)), font, fs, (255, 255, 255), th, cv2.LINE_AA)

            if idx < n_pos and not math.isnan(px[idx]):       # rastro contínuo + bola
                run = [(int(px[idx]), int(py[idx]), int(po[idx]))]
                for k in range(1, trail_len):
                    j = idx - k
                    if j < 0 or math.isnan(px[j]) or math.hypot(px[j] - px[j + 1], py[j] - py[j + 1]) > jump_lim:
                        break                                  # buraco ou salto absurdo: o rastro termina aqui
                    run.append((int(px[j]), int(py[j]), int(po[j + 1])))
                for (xa, ya, st), (xb, yb, _) in zip(run, run[1:]):   # laranja = trecho previsto
                    cv2.line(frame, (xa, ya), (xb, yb), (0, 140, 255) if st == 2 else (255, 255, 0), max(1, 2 * th), cv2.LINE_AA)
                state = run[0][2]
                color = {1: (0, 255, 0), 0: (0, 215, 255), 2: (0, 140, 255)}[state]
                cv2.circle(frame, run[0][:2], int(8 * sc), color, th + 1, cv2.LINE_AA)
                if state == 2:
                    cv2.putText(frame, "prevista", (run[0][0] + int(12 * sc), run[0][1] + int(18 * sc)), font, fs * 0.8,
                                color, th, cv2.LINE_AA)
                else:
                    cv2.circle(frame, run[0][:2], 2, color, -1)

            for e in past:
                if idx - e[0] > hold:
                    cv2.circle(frame, (e[1], e[2]), max(2, int(3 * sc)), (0, 0, 255), -1)   # impactos antigos
            for e in active:
                cv2.drawMarker(frame, (e[1], e[2]), (0, 0, 255), cv2.MARKER_TILTED_CROSS, int(24 * sc), th + 1)
                cv2.circle(frame, (e[1], e[2]), int(16 * sc), (0, 0, 255), th + 1, cv2.LINE_AA)
                label = f"IMPACTO Q{e[3]}" if e[3] else "IMPACTO (fora da grade)"
                (lw, _), _ = cv2.getTextSize(label, font, fs * 1.1, th + 2)
                lx = e[1] + int(14 * sc)
                if lx + lw > out_w - 4:                          # não deixa o texto sair da tela
                    lx = e[1] - int(14 * sc) - lw
                ly = max(int(18 * sc), e[2] - int(14 * sc))
                cv2.putText(frame, label, (lx, ly), font, fs * 1.1, (0, 0, 0), th + 2, cv2.LINE_AA)
                cv2.putText(frame, label, (lx, ly), font, fs * 1.1, (0, 255, 255), th, cv2.LINE_AA)

            hud = f"Impactos: {len(past)}   " + "  ".join(f"Q{q}:{counts[q]}" for q in counts)
            (tw, tht), _ = cv2.getTextSize(hud, font, fs, th)
            cv2.rectangle(frame, (0, out_h - tht - int(14 * sc)), (tw + int(14 * sc), out_h), (0, 0, 0), -1)
            cv2.putText(frame, hud, (int(7 * sc), out_h - int(7 * sc)), font, fs, (255, 255, 255), th, cv2.LINE_AA)
            writer.write(frame)
            if progress and idx % 30 == 0:
                progress(idx + 1, max(info.frames, idx + 1))
    finally:
        writer.release()
    if progress:
        progress(info.frames, info.frames)
    return codec


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def parse_hsv(text: str, name: str) -> tuple[int, int, int]:
    try:
        vals = tuple(int(v.strip()) for v in text.split(","))
        if len(vals) != 3 or not (0 <= vals[0] <= 179 and all(0 <= x <= 255 for x in vals[1:])):
            raise ValueError
        return vals
    except Exception as exc:
        raise ValueError(f"{name} deve ter o formato H,S,V (ex.: 20,65,55)") from exc


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="SmartTennis: bola + impacto por quadrante (OpenCV, sem ML).")
    p.add_argument("video", help="vídeo de entrada")
    p.add_argument("--xlsx", default=None, help="planilha de saída (padrão: <vídeo>_tracking.xlsx)")
    p.add_argument("--output", default=None, help="vídeo anotado (padrão: <vídeo>_tracking.mp4)")
    p.add_argument("--no-video", action="store_true", help="só a planilha, sem gerar o vídeo anotado")
    p.add_argument("--render-from", default=None, metavar="XLSX",
                   help="pula a análise e gera o vídeo a partir de uma planilha existente")
    p.add_argument("--render-width", type=int, default=960, help="largura do vídeo anotado (0 = original)")
    p.add_argument("--grid", default="3x2", help="COLUNASxLINHAS dos quadrantes (padrão 3x2 = 6)")
    p.add_argument("--area", default="", help="região dividida em quadrantes: 'x0,y0,x1,y1' normalizado (padrão: tela toda)")
    p.add_argument("--max-width", type=int, default=960, help="largura de processamento; 0 = original")
    p.add_argument("--roi", default="", help="polígono normalizado onde procurar a bola: 'x1,y1;x2,y2;...'")
    p.add_argument("--hsv-low", default="20,65,55")
    p.add_argument("--hsv-high", default="48,255,255")
    p.add_argument("--min-area", type=float, default=4.0, help="área mínima (px² em 960 px de largura)")
    p.add_argument("--max-area", type=float, default=900.0)
    p.add_argument("--min-circularity", type=float, default=0.18)
    p.add_argument("--max-jump", type=float, default=180.0, help="deslocamento máx. por quadro (px em 960 px)")
    p.add_argument("--max-gap", type=int, default=6, help="quadros sem detecção que ainda podem ser ligados")
    p.add_argument("--min-track", type=int, default=4, help="detecções mínimas por trecho de trajetória")
    p.add_argument("--min-speed", type=float, default=6.0,
                   help="velocidade mediana mínima (px/quadro em 960 px) para ser a bola em jogo; ignora bolas rolando/paradas")
    p.add_argument("--no-motion", action="store_true", help="desliga o filtro de movimento (câmera instável)")
    p.add_argument("--motion-thr", type=float, default=18.0)
    p.add_argument("--impact-angle", type=float, default=35.0, help="mudança mínima de direção (graus) p/ contar impacto")
    p.add_argument("--min-impact-gap", type=int, default=5, help="quadros mínimos entre dois impactos")
    p.add_argument("--impacts", choices=("parede", "todos", "chao"), default="parede",
                   help="parede (padrão) ignora quiques no chão; todos conta tudo; chao conta só quiques no chão")
    p.add_argument("--max-predict", type=int, default=12,
                   help="quadros em que a bola perdida segue sendo prevista (0 = não prever)")
    p.add_argument("--max-bridge", type=int, default=20, help="maior buraco (quadros) preenchido entre detecções coerentes")
    return p


def main() -> int:
    args = build_parser().parse_args()
    video = Path(args.video).expanduser()
    if not video.is_file():
        print(f"ERRO: vídeo não encontrado: {video}", file=sys.stderr)
        return 2
    xlsx = Path(args.xlsx).expanduser() if args.xlsx else video.with_name(video.stem + "_tracking.xlsx")
    out_video = Path(args.output).expanduser() if args.output else video.with_name(video.stem + "_tracking.mp4")

    def bar(label):
        last = [-1]

        def show(done: int, total: int) -> None:
            if done // 300 != last[0]:
                last[0] = done // 300
                print(f"{label}: {done}/{total or '?'} quadros", flush=True)
        return show

    try:
        if args.render_from:
            xlsx = Path(args.render_from).expanduser()
        else:
            cols, rows = parse_grid(args.grid)
            cfg = Config(hsv_low=parse_hsv(args.hsv_low, "--hsv-low"), hsv_high=parse_hsv(args.hsv_high, "--hsv-high"),
                         min_area=args.min_area, max_area=args.max_area,
                         min_circularity=max(0.0, min(1.0, args.min_circularity)), max_width=args.max_width,
                         roi=args.roi, use_motion=not args.no_motion, motion_thr=args.motion_thr,
                         max_jump=args.max_jump, max_gap=max(0, args.max_gap), min_speed=max(0.0, args.min_speed), min_track_len=max(3, args.min_track),
                         grid_cols=cols, grid_rows=rows, area=args.area, impact_angle=args.impact_angle,
                         min_impact_gap=max(1, args.min_impact_gap), impact_mode=args.impacts,
                         max_predict=max(0, args.max_predict), max_bridge=max(args.max_gap, args.max_bridge))
            print(f"Analisando {video.name} ...")
            result = analyze(video, cfg, bar("Análise"))
            xlsx.parent.mkdir(parents=True, exist_ok=True)
            write_xlsx(result, xlsx)
            print(f"\nPlanilha: {xlsx}")
            print(f"Quadros: {len(result.pos)} | com bola: {result.n_with_ball} "
                  f"({result.n_observed} detectados, {result.n_predicted} previstos)")
            print(f"Impactos: {len(result.impacts)}" + (f" (+{len(result.ignored)} ignorados pelo modo '{cfg.impact_mode}')" if result.ignored else ""))
            for q, n in result.counts.items():
                print(f"  Q{q} ({quadrant_label(q, result.cols, result.rows)}): {n}" + ("  <- bateu" if n else ""))
        if not args.no_video:
            res = read_xlsx(xlsx)
            out_video.parent.mkdir(parents=True, exist_ok=True)
            print("Gerando vídeo anotado a partir da planilha ...")
            codec = render_video(video, res, out_video, args.render_width, bar("Vídeo"))
            print(f"Vídeo anotado: {out_video} (codec {codec})")
    except (ValueError, RuntimeError) as exc:
        print(f"ERRO: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
