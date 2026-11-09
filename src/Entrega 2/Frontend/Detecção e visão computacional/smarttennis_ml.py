#!/usr/bin/env python3
"""SmartTennis: refino por ML da detecção clássica da bola.

Entra DEPOIS da detecção clássica (cor HSV + movimento + trajetória) e só trabalha onde ela
deixou lacunas, em janelas pequenas em volta da posição esperada da bola:

  1. A trajetória clássica (confiável) vira pseudo-rótulo: janelas com a bola (positivas) e
     janelas sem ela (negativas, incluindo candidatos amarelos que NÃO eram a bola).
  2. Um modelo de mapa de calor multi-quadro aprende o aspecto da bola neste vídeo:
       - "tracknet": rede U-Net compacta (PyTorch) no estilo TrackNet V1/V2/V3 (3 quadros
         empilhados + diferenças, mapa de calor gaussiano, perda WBCE do TrackNetV2);
       - "gbm": classificador por pixel (scikit-learn) com as mesmas entradas, sem PyTorch.
  3. Quadro a quadro nas lacunas: a posição esperada vem da trajetória balística entre as
     detecções vizinhas; o modelo olha só uma janela em volta dela; um pico de confiança
     dentro do portão de distância vira uma nova observação (origem 'refinada-ml').
  4. As observações novas voltam para a MESMA etapa de trajetória/impactos do tracker.

O refino só ADICIONA observações coerentes e não remove nenhuma detecção clássica.

Autoteste do modelo (sem vídeo):  python smarttennis_ml.py --selftest gbm|tracknet
"""
from __future__ import annotations

import argparse
import bisect
import math
import sys
import time
from pathlib import Path
from typing import Callable, Optional

import cv2
import numpy as np

import smarttennis_tracker as tk

SIGMA = 2.0            # desvio do mapa de calor gaussiano (px na escala do ML)
POS_HEAT = 0.6         # pixels com alvo >= isto são "bola" no GBM
SPAN = 16              # quadros vizinhos usados para prever a posição esperada
MIN_QUALITY = 0.5      # F0.5 mínimo na validação para o refino ser aceito
Log = Optional[Callable[[str], None]]


class MLUnavailable(RuntimeError):
    """Dependência ausente ou modelo sem qualidade mínima: o tracker segue só com a etapa clássica."""


# --------------------------------------------------------------------------
# Janelas, mapas de calor e picos
# --------------------------------------------------------------------------
def crop_xy(frame: np.ndarray, x0: int, y0: int, S: int) -> np.ndarray:
    """Janela SxS com canto superior esquerdo (x0, y0); fora do quadro replica a borda."""
    h, w = frame.shape[:2]
    xa, ya, xb, yb = max(0, x0), max(0, y0), min(w, x0 + S), min(h, y0 + S)
    if xb <= xa or yb <= ya:
        return np.zeros((S, S, 3), np.uint8)
    sub = frame[ya:yb, xa:xb]
    if sub.shape[0] == S and sub.shape[1] == S:
        return np.ascontiguousarray(sub)
    return cv2.copyMakeBorder(sub, ya - y0, y0 + S - yb, xa - x0, x0 + S - xb, cv2.BORDER_REPLICATE)


def crop_stack(f3, cx: float, cy: float, S: int):
    """Janela centrada em (cx, cy) dos 3 quadros (anterior, atual, seguinte): ([3,S,S,3] BGR, x0, y0)."""
    x0, y0 = int(round(cx - S / 2.0)), int(round(cy - S / 2.0))
    return np.stack([crop_xy(f, x0, y0, S) for f in f3]), x0, y0


_GRID: dict = {}


def make_heat(S: int, bx: float, by: float, sigma: float = SIGMA) -> np.ndarray:
    g = _GRID.get(S)
    if g is None:
        g = _GRID[S] = np.mgrid[0:S, 0:S].astype(np.float32)          # [2, S, S] = (y, x)
    return np.exp(-((g[1] - bx) ** 2 + (g[0] - by) ** 2) / (2.0 * sigma * sigma)).astype(np.float32)


def decode_peaks(heat: np.ndarray, thr: float, topk: int = 3) -> list:
    """Picos locais >= thr com posição sub-pixel (centróide do topo): [(x, y, confiança)] em ordem decrescente."""
    mx = cv2.dilate(heat, np.ones((5, 5), np.uint8))
    ys, xs = np.nonzero((heat >= mx) & (heat >= thr))
    if len(xs) == 0:
        return []
    h, w = heat.shape
    out: list = []
    for i in np.argsort(-heat[ys, xs]):
        x, y = int(xs[i]), int(ys[i])
        if any((x - p[3]) ** 2 + (y - p[4]) ** 2 <= 9 for p in out):
            continue
        peak = float(heat[y, x])
        xa, xb, ya, yb = max(0, x - 2), min(w, x + 3), max(0, y - 2), min(h, y + 3)
        win = np.clip(heat[ya:yb, xa:xb] - 0.3 * peak, 0.0, None)
        tot = float(win.sum())
        if tot > 0:
            gy, gx = np.mgrid[ya:yb, xa:xb]
            cx, cy = float((win * gx).sum() / tot), float((win * gy).sum() / tot)
        else:
            cx, cy = float(x), float(y)
        out.append((cx, cy, peak, x, y))
        if len(out) >= topk:
            break
    return [(p[0], p[1], p[2]) for p in out]


def triples(frames):
    """(t, anterior, atual, seguinte) a partir de um iterador de quadros; as pontas repetem o quadro."""
    a = b = None
    t = -1
    for f in frames:
        if b is None:
            a = b = f
            t = 0
            continue
        yield t, a, b, f
        a, b, t = b, f, t + 1
    if b is not None:
        yield t, a, b, b


# --------------------------------------------------------------------------
# Backend GBM (scikit-learn): classificador por pixel
# --------------------------------------------------------------------------
def _features(stack: np.ndarray) -> np.ndarray:
    """Características por pixel de uma janela [3,S,S,3]: cor, movimento entre os 3 quadros e contraste local."""
    prev, cur, nxt = stack[0], stack[1], stack[2]
    hsv = cv2.cvtColor(cur, cv2.COLOR_BGR2HSV).astype(np.float32)
    H, Sa, V = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    gp, gc, gn = (cv2.cvtColor(f, cv2.COLOR_BGR2GRAY).astype(np.float32) for f in (prev, cur, nxt))
    d1, d2 = gc - gp, gn - gc
    a1, a2 = np.abs(d1), np.abs(d2)
    dmin, dmax = np.minimum(a1, a2), np.maximum(a1, a2)

    def g12(im):
        return cv2.GaussianBlur(im, (0, 0), 1.2)

    def g35(im):
        return cv2.GaussianBlur(im, (0, 0), 3.5)

    dog_v, dog_s, dog_g = g12(V) - g35(V), g12(Sa) - g35(Sa), g12(gc) - g35(gc)
    sat_mean5 = cv2.blur(Sa, (5, 5))
    sat_max5 = cv2.dilate(Sa, np.ones((5, 5), np.uint8))
    v_con = V - cv2.blur(V, (9, 9))
    mot_blob = cv2.GaussianBlur(dmax, (0, 0), 1.5)
    mot_loc = cv2.blur(dmax, (9, 9))
    gx = cv2.Sobel(g12(gc), cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(g12(gc), cv2.CV_32F, 0, 1)
    grad = np.hypot(gx, gy)
    odd = np.abs(gc - np.median(np.stack([gp, gc, gn]), axis=0))   # só o quadro atual difere: o que se moveu
    odd_b = cv2.GaussianBlur(odd, (0, 0), 1.2)
    cols = [H, Sa, V, cur[..., 0], cur[..., 1], cur[..., 2], d1, d2, dmin, dmax, dog_v, dog_s, sat_mean5,
            sat_max5, v_con, mot_blob, mot_loc, grad, dog_g, odd, odd_b]
    return np.stack([np.asarray(c, np.float32).ravel() for c in cols], axis=1)


_C_DOG_V, _C_MOT_BLOB = 10, 15          # colunas usadas para escolher negativos difíceis


class GBMBackend:
    name = "gbm"

    def __init__(self, S: int):
        try:
            from sklearn.ensemble import HistGradientBoostingClassifier  # noqa: F401
        except ImportError as exc:
            raise MLUnavailable("scikit-learn não instalado (pip install scikit-learn)") from exc
        self.S = S
        self.model = None
        self.device = "cpu"

    def fit(self, stacks, balls, epochs: int = 0, log: Log = None, rng=None) -> None:
        from sklearn.ensemble import HistGradientBoostingClassifier
        rng = rng or np.random.default_rng(0)
        S = self.S
        X, y = [], []
        for stack, ball in zip(stacks, balls):
            F = _features(stack)
            h = np.zeros(S * S, np.float32) if np.isnan(ball[0]) else make_heat(S, ball[0], ball[1]).ravel()
            pos = np.flatnonzero(h >= POS_HEAT)
            neg = np.flatnonzero(h < 0.05)
            pick = [pos]
            if len(neg):
                pick.append(rng.choice(neg, size=min(len(neg), 40), replace=False))
                susp = np.abs(F[neg, _C_DOG_V]) + F[neg, _C_MOT_BLOB]       # negativos difíceis: o que mais "salta"
                pick.append(neg[np.argsort(-susp)[:25]])
            rows = np.concatenate(pick)
            X.append(F[rows])
            y.append(h[rows] >= POS_HEAT)
        X, y = np.concatenate(X), np.concatenate(y)
        if y.sum() < 20 or (~y).sum() < 20:
            raise MLUnavailable("amostras insuficientes para treinar o modelo")
        if log:
            log(f"GBM: treinando com {len(y):,} pixels ({int(y.sum()):,} de bola)")
        self.model = HistGradientBoostingClassifier(max_iter=160, max_depth=7, learning_rate=0.12,
                                                    l2_regularization=1.0, min_samples_leaf=20,
                                                    early_stopping=False, random_state=0).fit(X, y)

    def heat(self, stacks) -> np.ndarray:
        S, out = self.S, []
        for i in range(0, len(stacks), 32):
            chunk = stacks[i:i + 32]
            p = self.model.predict_proba(np.concatenate([_features(s) for s in chunk]))[:, 1]
            for h in p.reshape(len(chunk), S, S).astype(np.float32):
                out.append(cv2.GaussianBlur(h, (0, 0), 1.0))
        return np.stack(out) if out else np.zeros((0, S, S), np.float32)

    def save(self, path: Path, meta: dict) -> None:
        import joblib
        joblib.dump({"backend": "gbm", "model": self.model, "meta": meta}, str(path))

    @classmethod
    def load(cls, path: Path, S: int):
        import joblib
        blob = joblib.load(str(path))
        be = cls(S)
        be.model = blob["model"]
        return be, blob["meta"]


# --------------------------------------------------------------------------
# Backend TrackNet leve (PyTorch)
# --------------------------------------------------------------------------
def _build_tracknet(torch, base: int):
    """U-Net compacta no estilo TrackNet: entrada = 3 quadros RGB (9 canais) + 2 diferenças (6 canais),
    saída = logits do mapa de calor (1 canal) na resolução da janela. Janela múltipla de 8."""
    nn = torch.nn

    def block(i, o):
        return nn.Sequential(nn.Conv2d(i, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True),
                             nn.Conv2d(o, o, 3, padding=1, bias=False), nn.BatchNorm2d(o), nn.ReLU(inplace=True))

    class TrackNetLite(nn.Module):
        def __init__(self):
            super().__init__()
            b = base
            self.e1, self.e2, self.e3 = block(15, b), block(b, 2 * b), block(2 * b, 4 * b)
            self.mid = block(4 * b, 6 * b)
            self.d3, self.d2, self.d1 = block(6 * b + 4 * b, 4 * b), block(4 * b + 2 * b, 2 * b), block(2 * b + b, b)
            self.pool = nn.MaxPool2d(2)
            self.up = nn.Upsample(scale_factor=2, mode="bilinear", align_corners=False)
            self.head = nn.Conv2d(b, 1, 1)

        def forward(self, x):                                   # x: [B, 9, S, S] em 0..1 (anterior, atual, seguinte)
            prev, cur, nxt = x[:, 0:3], x[:, 3:6], x[:, 6:9]
            z = torch.cat([x, (cur - prev).abs(), (nxt - cur).abs()], dim=1)
            e1 = self.e1(z)
            e2 = self.e2(self.pool(e1))
            e3 = self.e3(self.pool(e2))
            m = self.mid(self.pool(e3))
            d3 = self.d3(torch.cat([self.up(m), e3], dim=1))
            d2 = self.d2(torch.cat([self.up(d3), e2], dim=1))
            d1 = self.d1(torch.cat([self.up(d2), e1], dim=1))
            return self.head(d1)

    return TrackNetLite()


def _torch_device(torch):
    if torch.cuda.is_available():
        try:
            (torch.ones(8, device="cuda") * 2).sum().item()      # placas antigas podem não ter kernels no build
            return torch.device("cuda")
        except Exception:
            pass
    return torch.device("cpu")


class TrackNetBackend:
    name = "tracknet"

    def __init__(self, S: int, base: Optional[int] = None, device=None):
        try:
            import torch
        except ImportError as exc:
            raise MLUnavailable("PyTorch não instalado (pip install torch)") from exc
        self.torch = torch
        self.S = S
        self.device = device or _torch_device(torch)
        self.base = base or (16 if self.device.type == "cuda" else 8)
        self.net = None

    def _tensor(self, stacks):
        a = np.ascontiguousarray(np.stack(stacks))                                   # [N, 3, S, S, 3]
        return self.torch.from_numpy(a).permute(0, 1, 4, 2, 3).reshape(len(a), 9, self.S, self.S)

    @staticmethod
    def _wbce(torch, logits, y):
        """Perda ponderada do TrackNetV2: (1-p)^2 pesa os positivos e p^2 os negativos."""
        p = torch.sigmoid(logits).clamp(1e-6, 1.0 - 1e-6)
        loss = -(((1.0 - p) ** 2) * y * torch.log(p) + (p ** 2) * (1.0 - y) * torch.log(1.0 - p))
        return loss.sum(dim=(1, 2, 3)).mean()

    @staticmethod
    def _augment(torch, x, y, rng):
        """Espelha, embaralha canais de cor (para não depender só do amarelo), tons de cinza, brilho e ruído."""
        B, S = x.shape[0], x.shape[-1]
        for i in range(B):
            if rng.random() < 0.5:
                x[i], y[i] = x[i].flip(-1), y[i].flip(-1)
            if rng.random() < 0.15:
                x[i], y[i] = x[i].flip(-2), y[i].flip(-2)
            r = rng.random()
            if r < 0.35:
                perm = rng.permutation(3)
                x[i] = x[i][[f * 3 + int(c) for f in range(3) for c in perm]]
            elif r < 0.5:
                x[i] = x[i].reshape(3, 3, S, S).mean(dim=1, keepdim=True).expand(3, 3, S, S).reshape(9, S, S)
            a, b = 0.8 + 0.4 * rng.random(), (rng.random() - 0.5) * 0.15
            x[i] = (x[i] * float(a) + float(b)).clamp(0.0, 1.0)
        return (x + 0.01 * torch.randn_like(x)).clamp(0.0, 1.0), y

    def fit(self, stacks, balls, epochs: int = 0, log: Log = None, rng=None) -> None:
        torch = self.torch
        rng = rng or np.random.default_rng(0)
        S, N = self.S, len(stacks)
        if N < 16:
            raise MLUnavailable("amostras insuficientes para treinar o TrackNet")
        X = self._tensor(stacks)                                                       # uint8 [N, 9, S, S]
        Y = torch.from_numpy(np.stack([np.zeros((S, S), np.float32) if np.isnan(b[0]) else make_heat(S, b[0], b[1])
                                       for b in balls])[:, None])
        epochs = epochs or (14 if self.device.type == "cuda" else 8)
        bs = 32
        nb = max(1, N // bs)
        for attempt in range(2):
            try:
                net = _build_tracknet(torch, self.base).to(self.device)
                opt = torch.optim.AdamW(net.parameters(), lr=2e-3, weight_decay=1e-4)
                sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=epochs * nb)
                if log:
                    log(f"TrackNet: treinando {epochs} épocas em {self.device.type} "
                        f"({sum(p.numel() for p in net.parameters()):,} parâmetros, {N} janelas)")
                for ep in range(epochs):
                    net.train()
                    order = rng.permutation(N)
                    total = 0.0
                    for b in range(nb):
                        idx = torch.as_tensor(order[b * bs:(b + 1) * bs], dtype=torch.long)
                        if len(idx) < 2:
                            continue                                                   # BatchNorm precisa de >= 2
                        xb = (X[idx].float() / 255.0)
                        yb = Y[idx].clone()
                        xb, yb = self._augment(torch, xb, yb, rng)
                        loss = self._wbce(torch, net(xb.to(self.device)), yb.to(self.device))
                        opt.zero_grad(set_to_none=True)
                        loss.backward()
                        opt.step()
                        sched.step()
                        total += float(loss.item())
                    if log and (ep == 0 or (ep + 1) % 4 == 0 or ep + 1 == epochs):
                        log(f"TrackNet: época {ep + 1}/{epochs}  perda {total / nb:.2f}")
                self.net = net
                return
            except RuntimeError as exc:
                if attempt == 0 and self.device.type == "cuda":                        # GPU sem kernels: tenta CPU
                    if log:
                        log(f"AVISO: GPU falhou ({exc}); treinando na CPU")
                    self.device, self.base = torch.device("cpu"), min(self.base, 8)
                    continue
                raise

    def heat(self, stacks) -> np.ndarray:
        torch = self.torch
        self.net.eval()
        out = []
        with torch.no_grad():
            for i in range(0, len(stacks), 64):
                x = self._tensor(stacks[i:i + 64]).to(self.device).float() / 255.0
                out.append(torch.sigmoid(self.net(x))[:, 0].cpu().numpy())
        return np.concatenate(out, 0).astype(np.float32) if out else np.zeros((0, self.S, self.S), np.float32)

    def save(self, path: Path, meta: dict) -> None:
        self.torch.save({"backend": "tracknet", "state": self.net.state_dict(), "base": self.base, "meta": meta},
                        str(path))

    @classmethod
    def load(cls, path: Path, S: int):
        import torch
        blob = torch.load(str(path), map_location="cpu")
        be = cls(S, base=int(blob["base"]))
        be.net = _build_tracknet(torch, be.base)
        be.net.load_state_dict(blob["state"])
        be.net.to(be.device)
        return be, blob["meta"]


# --------------------------------------------------------------------------
# Treino, validação e calibração do limiar
# --------------------------------------------------------------------------
def calibrate(heats: np.ndarray, balls: np.ndarray, tol: float = 3.0) -> dict:
    """Escolhe o limiar de confiança que maximiza o F0.5 (precisão pesa mais: falso positivo vira falso impacto)."""
    top = []
    for h, b in zip(heats, balls):
        has = not np.isnan(b[0])
        pk = decode_peaks(h, 0.02, topk=1)
        if pk:
            x, y, c = pk[0]
            top.append((c, has and math.hypot(x - b[0], y - b[1]) <= tol, has))
        else:
            top.append((0.0, False, has))
    rows = []
    for thr in np.arange(0.05, 0.96, 0.025):
        tp = sum(1 for c, ok, _ in top if c >= thr and ok)
        fp = sum(1 for c, ok, _ in top if c >= thr and not ok)
        fn = sum(1 for c, ok, has in top if has and not (c >= thr and ok))
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f05 = 1.25 * prec * rec / (0.25 * prec + rec) if prec + rec > 0 else 0.0
        rows.append((f05, float(thr), prec, rec))
    top_f = max(r[0] for r in rows)
    plateau = [r for r in rows if r[0] >= top_f - 0.01]          # vários limiares empatam: usa o do meio do platô
    f05, thr_, prec, rec = plateau[len(plateau) // 2]
    best = {"f05": f05, "thr": thr_, "precision": prec, "recall": rec}
    best["n_pos"] = int(sum(1 for t in top if t[2]))
    best["n"] = len(top)
    return best


def train_backend(backend, stacks, balls, fids, epochs: int, log: Log):
    """Treina e valida (blocos de 20 quadros: 1 em cada 4 vai para a validação, sem vazamento temporal)."""
    balls = np.asarray(balls, np.float32)
    fids = np.asarray(fids)
    val = (fids // 20) % 4 == 3
    if val.sum() < 10 or (~val).sum() < 20:
        val = np.arange(len(fids)) % 4 == 3
    tr_idx, va_idx = np.flatnonzero(~val), np.flatnonzero(val)
    backend.fit([stacks[i] for i in tr_idx], balls[tr_idx], epochs=epochs, log=log)
    heats = backend.heat([stacks[i] for i in va_idx])
    cal = calibrate(heats, balls[va_idx])
    if log:
        log(f"{backend.name}: validação em {cal['n']} janelas ({cal['n_pos']} com bola) -> limiar {cal['thr']:.2f}, "
            f"precisão {cal['precision']:.0%}, recall {cal['recall']:.0%}")
    return cal


# --------------------------------------------------------------------------
# Coleta de amostras a partir da trajetória clássica (pseudo-rótulos)
# --------------------------------------------------------------------------
def collect_samples(video_path, mlw: int, mlh: int, S: int, obs_ml: dict, cands, k: float, n_frames: int,
                    rng, max_pos: int, progress=None):
    """Janelas positivas (bola em posição aleatória da janela) e negativas (candidato que não era a bola ou
    lugar qualquer) em quadros onde a bola é conhecida: ali, tudo que está longe dela com certeza não é a bola."""
    ts = sorted(obs_ml)
    if len(ts) > max_pos:
        ts = [ts[i] for i in np.linspace(0, len(ts) - 1, max_pos).astype(int)]
    sel = set(ts)
    last = max(sel)
    margin, lim = 10, S / 2.0 - 10
    stacks, balls, fids = [], [], []
    frames = tk.iter_frames(video_path, (mlw, mlh))
    gen = triples(frames)
    try:
        for t, a, b, c in gen:
            if t in sel:
                bx, by = obs_ml[t]
                ox, oy = np.clip(rng.normal(0.0, S / 5.0, 2), -lim, lim)
                st, x0, y0 = crop_stack((a, b, c), bx + ox, by + oy, S)                 # positivo
                stacks.append(st)
                balls.append((bx - x0, by - y0))
                fids.append(t)
                hard = [(x * k, y * k) for x, y, _, _ in cands[t] if math.hypot(x * k - bx, y * k - by) > 12.0]
                if hard and rng.random() < 0.6:                                         # negativo difícil
                    hx, hy = hard[int(rng.integers(len(hard)))]
                    cx, cy = hx + rng.normal(0, 4), hy + rng.normal(0, 4)
                else:                                                                   # negativo qualquer
                    cx, cy = rng.uniform(S / 2, max(S / 2 + 1, mlw - S / 2)), rng.uniform(S / 2, max(S / 2 + 1, mlh - S / 2))
                st, x0, y0 = crop_stack((a, b, c), cx, cy, S)
                bxc, byc = bx - x0, by - y0
                inside = 3 <= bxc <= S - 4 and 3 <= byc <= S - 4                        # a bola pode cair na janela
                stacks.append(st)
                balls.append((bxc, byc) if inside else (np.nan, np.nan))
                fids.append(t)
            if progress and t % 30 == 0:
                progress(t, n_frames)
            if t >= last:
                break
    finally:
        gen.close()
        frames.close()
    return stacks, balls, fids


# --------------------------------------------------------------------------
# Posição esperada da bola numa lacuna
# --------------------------------------------------------------------------
class Known:
    """Posições conhecidas (clássicas + achadas pelo ML), ordenadas no tempo, em px da escala do ML."""

    def __init__(self, obs_ml: dict):
        self.ts = sorted(obs_ml)
        self.xs = [obs_ml[t][0] for t in self.ts]
        self.ys = [obs_ml[t][1] for t in self.ts]

    def add(self, t: int, x: float, y: float) -> None:
        i = bisect.bisect_left(self.ts, t)
        self.ts.insert(i, t)
        self.xs.insert(i, x)
        self.ys.insert(i, y)

    def around(self, t: int):
        i = bisect.bisect_left(self.ts, t)
        j = bisect.bisect_right(self.ts, t)
        before = [(self.ts[q], self.xs[q], self.ys[q]) for q in range(max(0, i - 4), i) if t - self.ts[q] <= SPAN]
        after = [(self.ts[q], self.xs[q], self.ys[q]) for q in range(j, min(len(self.ts), j + 4))
                 if self.ts[q] - t <= SPAN]
        return before, after


def expected_positions(known: Known, t: int, ms: float, grav: float, S: int) -> list:
    """Onde a bola deve estar no quadro t: lista de (x, y, sigma, portão) pela trajetória balística dos vizinhos.
    Entre duas detecções que concordam, as previsões dos dois lados são combinadas (um só ponto). Se discordam
    (houve quique/impacto no meio) cada lado vale só até o impacto, então os dois são testados. Lista vazia se
    a incerteza passa da janela (sem base para procurar)."""
    before, after = known.around(t)

    def side(pts, near):
        if len(pts) < 2:
            return None
        f = tk._motion_fit(pts, ms, grav)
        ta, xa, ya = near
        x, y = f.pos(t)
        px, py = f.pos(ta)
        return x + (xa - px), y + (ya - py), f.speed

    pb = side(before, before[-1]) if before else None
    pa = side(after, after[0]) if after else None
    base, limit = 3.0 * ms, S / 2.0 - 5.0
    if pb is None and pa is None:
        return []
    sides = []                                                   # (previsão, quadros até a âncora desse lado)
    if pb is not None:
        sides.append((pb, t - before[-1][0]))
    if pa is not None:
        sides.append((pa, after[0][0] - t))
    if pb is not None and pa is not None:
        db, da = sides[0][1], sides[1][1]
        wb, wa = 1.0 / db, 1.0 / da
        x, y = (wb * pb[0] + wa * pa[0]) / (wb + wa), (wb * pb[1] + wa * pa[1]) / (wb + wa)
        gap = math.hypot(pb[0] - pa[0], pb[1] - pa[1])
        sigma = base + 0.35 * gap + 0.08 * max(pb[2], pa[2]) * min(db, da)
        gate = max(6.0 * ms, 2.5 * sigma)
        if gap <= 3.0 * base and gate <= limit:
            return [(x, y, sigma, gate)]
    out = []
    for (x, y, spd), dt in sides:
        sigma = base + 0.12 * spd * dt
        gate = max(6.0 * ms, 2.5 * sigma)
        if gate <= limit:
            out.append((x, y, sigma, gate))
    return out


def neighbor_priors(known: Known, t: int, vmax: float, ms: float, S: int) -> list:
    """Plano B quando o palpite balístico não acha nada (ex.: dois quiques em poucos quadros, onde nenhum
    lado descreve o trecho do meio): procura em volta do vizinho conhecido mais próximo (até 2 quadros),
    com raio limitado pela velocidade máxima vista na própria trajetória."""
    before, after = known.around(t)
    out = []
    for near in (before[-1] if before else None, after[0] if after else None):
        if near is None or abs(near[0] - t) > 2:
            continue
        gate = min(S / 2.0 - 5.0, vmax * abs(near[0] - t) + 3.0 * ms)
        out.append((near[1], near[2], gate / 2.5, gate))
    return out


def plan_chunks(pos0: list, obs_times: list, n: int, ext: int, max_chunk: int = 24) -> list:
    """Quadros a refinar: os que a etapa clássica só interpolou/previu, mais uma extensão para trás do início
    de cada trecho. Agrupados em blocos contíguos, cada um processado a partir da âncora conhecida."""
    target = {t for t, p in enumerate(pos0) if p is not None and p[2] in (0, 2)}
    obs_set = set(obs_times)
    for t_o in obs_times:                                  # estende para trás o início de cada trecho
        for d in range(1, ext + 1):
            t = t_o - d
            if t < 0 or pos0[t] is not None:
                break
            target.add(t)
    chunks, run = [], []
    for t in sorted(target):
        if run and (t != run[-1] + 1 or len(run) >= max_chunk):
            chunks.append(run)
            run = []
        run.append(t)
    if run:
        chunks.append(run)
    plan = []
    for r in chunks:
        t0, t1 = r[0], r[-1]
        backward = (t0 - 1) not in obs_set and (t1 + 1) in obs_set and (t0 - 1) not in target
        plan.append({"t0": t0, "t1": t1, "backward": backward, "trigger": min(t1 + 1, n - 1)})
    plan.sort(key=lambda c: c["trigger"])
    return plan


# --------------------------------------------------------------------------
# Refino
# --------------------------------------------------------------------------
def _weights_path(cfg, name: str) -> Optional[Path]:
    if not cfg.ml_weights:
        return None
    p = Path(cfg.ml_weights).expanduser()
    return p.with_suffix(".pt") if name == "tracknet" else p.with_suffix(".gbm.joblib")


def _backend_order(cfg) -> list:
    names = {"gbm": ["gbm"], "tracknet": ["tracknet", "gbm"]}.get(cfg.ml)
    if names is None:                                # auto: TrackNet se houver PyTorch
        try:
            import torch  # noqa: F401
            names = ["tracknet", "gbm"]
        except ImportError:
            names = ["gbm"]
    return names


def refine(video_path, scan, obs: list, grav: float, cfg, progress=None, log: Log = None):
    """Preenche lacunas da trajetória clássica. Retorna (observações ampliadas, quadros achados pelo ML, info)."""
    t_start = time.time()
    say = log or (lambda m: None)
    rng = np.random.default_rng(0)
    info, n, S = scan.info, scan.n_frames, cfg.ml_crop
    if len(obs) < 12:
        raise MLUnavailable(f"só {len(obs)} detecções clássicas: poucas para treinar o modelo (mínimo 12)")
    mlw = min(int(cfg.ml_width), info.width)
    mlw -= mlw % 2
    mlh = max(2, int(round(info.height * mlw / info.width)))
    k = mlw / scan.pw                                            # px de processamento -> px do ML
    ms = mlw / tk.REF_WIDTH                                      # a 'scale' do tracker, na escala do ML
    obs_ml = {t: (x * k, y * k) for t, x, y in obs}

    # ---- 1) modelo: carrega ou treina com a trajetória clássica
    backend, meta, last_err = None, None, None
    for name in _backend_order(cfg):
        wpath = _weights_path(cfg, name)
        try:
            if wpath is not None and wpath.exists() and not cfg.ml_retrain:
                be, meta = (TrackNetBackend if name == "tracknet" else GBMBackend).load(wpath, S)
                if meta.get("S") == S and meta.get("mlw") == mlw:
                    backend = be
                    say(f"Modelo {name} carregado de {wpath} (limiar {meta['thr']:.2f})")
                    break
                say(f"AVISO: {wpath} foi treinado com outra janela/largura; treinando de novo")
            say(f"Refino ML: coletando janelas de treino ({name}) ...")
            stacks, balls, fids = collect_samples(video_path, mlw, mlh, S, obs_ml, scan.cands, k, n, rng,
                                                  max_pos=600, progress=progress)
            be = TrackNetBackend(S) if name == "tracknet" else GBMBackend(S)
            t_fit = time.time()
            cal = train_backend(be, stacks, balls, fids, cfg.ml_epochs, say)
            if cal["f05"] < MIN_QUALITY or cal["n_pos"] < 15:
                raise MLUnavailable(f"{name} sem qualidade mínima na validação (F0.5={cal['f05']:.2f}, "
                                    f"{cal['n_pos']} janelas com bola)")
            meta = {"backend": name, "S": S, "mlw": mlw, "thr": cal["thr"], "precision": cal["precision"],
                    "recall": cal["recall"], "f05": cal["f05"], "n_val": cal["n"], "n_val_pos": cal["n_pos"]}
            backend = be
            say(f"Treino {name}: {time.time() - t_fit:.1f} s")
            if wpath is not None:
                wpath.parent.mkdir(parents=True, exist_ok=True)
                be.save(wpath, meta)
                say(f"Modelo salvo em {wpath}")
            break
        except MLUnavailable as exc:
            last_err = exc
            say(f"AVISO: {exc}" + ("; tentando o próximo modelo" if name != _backend_order(cfg)[-1] else ""))
        except Exception as exc:                                  # erro inesperado no backend: tenta o seguinte
            last_err = exc
            say(f"AVISO: {name} falhou ({type(exc).__name__}: {exc})"
                + ("; tentando o próximo modelo" if name != _backend_order(cfg)[-1] else ""))
    if backend is None:
        raise MLUnavailable(str(last_err) if last_err else "nenhum modelo disponível")
    thr = cfg.ml_conf if cfg.ml_conf > 0 else float(meta["thr"])

    # ---- 2) lacunas a preencher
    pos0 = tk.build_positions(obs, n, scan.scale, cfg.max_gap, cfg.max_bridge, cfg.max_predict, scan.pw, scan.ph, grav)
    plan = plan_chunks(pos0, [t for t, _, _ in obs], n, max(0, cfg.max_predict))
    n_targets = sum(c["t1"] - c["t0"] + 1 for c in plan)
    say(f"Refino ML: {n_targets} quadros em {len(plan)} lacunas para tentar (limiar {thr:.2f})")
    known = Known(obs_ml)
    steps = [math.hypot(obs_ml[t][0] - obs_ml[t - 1][0], obs_ml[t][1] - obs_ml[t - 1][1]) for t in obs_ml if t - 1 in obs_ml]
    vmax = max(8.0 * ms, 1.25 * float(np.percentile(steps, 95))) if steps else 20.0 * ms
    found: dict = {}
    grav_ml = grav * k

    def process(chunk, buf):
        def fr(t):
            return buf[min(max(t, 0), n - 1)]
        order = range(chunk["t1"], chunk["t0"] - 1, -1) if chunk["backward"] else range(chunk["t0"], chunk["t1"] + 1)
        def search(t, priors, f3):
            best = None
            for px, py, _, gate in priors:
                st, x0, y0 = crop_stack(f3, px, py, S)
                for cx, cy, conf in decode_peaks(backend.heat([st])[0], thr, topk=4):
                    X, Y = x0 + cx, y0 + cy
                    d = math.hypot(X - px, Y - py)
                    if d > gate:
                        continue
                    score = conf * math.exp(-0.5 * (d / (gate / 2.0)) ** 2)
                    if best is None or score > best[0]:
                        best = (score, X, Y)
            return best

        for t in order:
            f3 = (fr(t - 1), fr(t), fr(t + 1))
            best = search(t, expected_positions(known, t, ms, grav_ml, S), f3)
            if best is None:
                best = search(t, neighbor_priors(known, t, vmax, ms, S), f3)
            if best is not None:
                found[t] = (best[1], best[2])
                known.add(t, best[1], best[2])

    # ---- 3) uma passada pelo vídeo: cada lacuna é processada quando seu último quadro vizinho chega
    if plan:
        buf: dict = {}
        pending = list(plan)
        last_trigger = plan[-1]["trigger"]
        frames = tk.iter_frames(video_path, (mlw, mlh))
        try:
            for i, frame in enumerate(frames):
                buf[i] = frame
                while pending and pending[0]["trigger"] == i:
                    process(pending.pop(0), buf)
                for old in [q for q in buf if q < i - 30]:
                    del buf[old]
                if progress and i % 30 == 0:
                    progress(i, n)
                if i >= last_trigger and not pending:
                    break
        finally:
            frames.close()

    new_obs = sorted(list(obs) + [(t, x / k, y / k) for t, (x, y) in found.items()])
    elapsed = time.time() - t_start
    say(f"Refino ML: {len(found)} de {n_targets} quadros preenchidos em {elapsed:.1f} s")
    dev = getattr(backend, "device", "cpu")
    dev = getattr(dev, "type", dev)
    info_d = {
        "ML: modelo": f"{backend.name} ({dev}), janela {S} px a {mlw} px de largura",
        "ML: limiar de confiança": f"{thr:.2f}",
        "ML: validação (pseudo-rótulos, otimista)":
            f"precisão {meta['precision']:.0%}, recall {meta['recall']:.0%} em {meta['n_val']} janelas",
        "ML: quadros tentados / preenchidos": f"{n_targets} / {len(found)}",
        "ML: tempo (s)": f"{elapsed:.1f}",
    }
    return new_obs, sorted(found), info_d


# --------------------------------------------------------------------------
# Autoteste do modelo (sem vídeo): valida que o backend aprende e prevê no seu computador
# --------------------------------------------------------------------------
def _synthetic_sample(rng, S: int, with_ball: bool):
    bg = cv2.resize(rng.integers(50, 170, (6, 6, 3)).astype(np.uint8), (S, S), interpolation=cv2.INTER_CUBIC)
    for _ in range(2):
        p = rng.integers(0, S, 4)
        cv2.line(bg, (int(p[0]), int(p[1])), (int(p[2]), int(p[3])), (230, 230, 230), 1)
    frames = [bg.copy() for _ in range(3)]
    rx, ry = rng.integers(8, S - 20, 2)                                           # retângulo amarelo PARADO (distrator)
    for f in frames:
        cv2.rectangle(f, (int(rx), int(ry)), (int(rx) + 12, int(ry) + 6), (40, 200, 235), -1)
    gx, gy = rng.uniform(15, S - 15, 2)                                           # mancha cinza que se move (distrator)
    gd = rng.uniform(0, 2 * math.pi)
    for q, f in enumerate(frames):
        cv2.circle(f, (int(gx + 5 * (q - 1) * math.cos(gd)), int(gy + 5 * (q - 1) * math.sin(gd))), 4, (120, 120, 120), -1)
    p0 = rng.uniform(22, S - 22, 2)
    ang, sp = rng.uniform(0, 2 * math.pi), rng.uniform(5, 14)
    pts = [p0 + (q - 1) * sp * np.array([math.cos(ang), math.sin(ang)]) for q in range(3)]
    col = cv2.cvtColor(np.uint8([[[32, rng.uniform(60, 230), 235]]]), cv2.COLOR_HSV2BGR)[0, 0].tolist()
    if with_ball:
        for q, f in enumerate(frames):
            cv2.circle(f, (int(round(pts[q][0])), int(round(pts[q][1]))), 4, col, -1, cv2.LINE_AA)
    st = np.stack([np.clip(f.astype(np.float32) + rng.normal(0, 3, f.shape), 0, 255).astype(np.uint8) for f in frames])
    return st, ((pts[1][0], pts[1][1]) if with_ball else (np.nan, np.nan))


def selftest(name: str, n: int = 480, S: int = 96) -> bool:
    rng = np.random.default_rng(1)
    t0 = time.time()
    try:
        backend = TrackNetBackend(S) if name == "tracknet" else GBMBackend(S)
    except MLUnavailable as exc:
        print(f"[selftest] {name}: indisponível — {exc}")
        return False
    stacks, balls = [], []
    for i in range(n):
        st, b = _synthetic_sample(rng, S, with_ball=(i % 2 == 0))
        stacks.append(st)
        balls.append(b)
    cal = train_backend(backend, stacks, balls, np.arange(n), 0, print)
    ok = cal["precision"] >= 0.8 and cal["recall"] >= 0.7
    print(f"[selftest] {name}: {'OK' if ok else 'FALHOU'} em {time.time() - t0:.1f} s "
          f"(precisão {cal['precision']:.0%}, recall {cal['recall']:.0%}; esperado >= 80% / 70%)")
    return ok


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="SmartTennis: autoteste do modelo de refino.")
    ap.add_argument("--selftest", choices=("gbm", "tracknet"), required=True)
    sys.exit(0 if selftest(ap.parse_args().selftest) else 1)
