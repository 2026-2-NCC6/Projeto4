#!/usr/bin/env python3
"""Servidor local headless do SmartTennis.

Abre a webcam automaticamente, processa a stream quadro a quadro com o tracker OpenCV
existente e disponibiliza eventos HTTP para o aplicativo Android. Não requer Streamlit,
seleção manual de vídeo ou interação depois da inicialização.

Iniciar: python smarttennis_server.py
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import signal
import socket
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse, parse_qs

import cv2

import smarttennis_tracker as tracker

SERVICE_NAME = "smarttennis-camera"
DISCOVERY_PORT_DEFAULT = 37020
HTTP_PORT_DEFAULT = 8765
DISCOVERY_REQUEST = b"SMARTTENNIS_CAMERA_DISCOVER_V1"


class LiveImpactTracker:
    """Reaproveita o detector e a ligação de trajetória do tracker, em janela deslizante."""

    def __init__(self, width: int, height: int, fps: float, camera_mode: str,
                 roi: str = "", wall_area: str = "", tracker_options: dict[str, Any] | None = None):

        self.original_width = int(width)
        self.original_height = int(height)
        self.fps = max(10.0, min(60.0, float(fps or 30.0)))
        options = dict(tracker_options or {})
        # A grade 3x2 é mantida porque as metas de precisão e quadrantes do app
        # são definidas sobre Q1..Q6. Os demais valores espelham os padrões
        # iniciais da interface Streamlit original e podem ser afinados por CLI.
        options.update({
            "grid_cols": 3,
            "grid_rows": 2,
            "impact_mode": "parede",
            "roi": roi.strip(),
            "area": wall_area.strip(),
        })
        self.cfg = tracker.Config(**options)
        self.cfg.validate()
        pw = self.original_width if self.cfg.max_width <= 0 else min(self.cfg.max_width, self.original_width)
        ph = max(2, int(round(self.original_height * pw / self.original_width)))
        self.process_width, self.process_height = pw, ph
        self.scale_x = self.original_width / pw
        self.scale_y = self.original_height / ph
        polygon = tracker.parse_polygon(self.cfg.roi, pw, ph)
        self.detector = tracker.BallDetector(pw, ph, self.cfg, polygon)
        self.area = tracker.parse_area(self.cfg.area)
        self.camera_mode = camera_mode
        self.max_frames = max(100, int(self.fps * 5.5))
        self.candidates: deque[list] = deque(maxlen=self.max_frames)
        self.timestamps: deque[int] = deque(maxlen=self.max_frames)
        self.cut_flags: deque[int] = deque(maxlen=self.max_frames)
        self.total_frames = 0
        self.last_analysis_frame = -1
        self.last_emitted_frame = -10_000
        self.latest_fps = self.fps

    def process(self, frame, epoch_ms: int) -> list[dict[str, Any]]:
        if frame is None or frame.size == 0:
            return []
        if (frame.shape[1], frame.shape[0]) != (self.process_width, self.process_height):
            frame = cv2.resize(frame, (self.process_width, self.process_height), interpolation=cv2.INTER_AREA)
        found, state = self.detector.detect(frame)
        self.candidates.append(found)
        self.timestamps.append(int(epoch_ms))
        self.cut_flags.append(1 if state == "cut" else 0)
        self.total_frames += 1

        # Analisar a cada 3 quadros reduz o custo da DP sem perder os impactos rápidos.
        if len(self.candidates) < 16 or self.total_frames - self.last_analysis_frame < 3:
            return []
        self.last_analysis_frame = self.total_frames
        return self._detectar_eventos()

    def _detectar_eventos(self) -> list[dict[str, Any]]:
        cands = list(self.candidates)
        n = len(cands)
        cuts = []
        acum = 0
        for flag in self.cut_flags:
            acum += flag
            cuts.append(acum)
        scale = self.detector.scale
        chains = tracker.link_trajectory(
            cands, cuts, scale, self.cfg.max_jump * scale,
            self.cfg.max_gap, self.cfg.min_track_len, self.cfg.min_speed * scale,
        )
        chains = [chain for chain in (tracker.clean_chain(c, scale, self.cfg.min_track_len) for c in chains) if chain]
        if not chains:
            return []
        gravity = tracker.estimate_gravity(chains, scale)
        obs = tracker.select_track(chains, scale, self.cfg.max_bridge, gravity)
        if len(obs) < 5:
            return []
        impacts = tracker.detect_impacts(obs, scale, self.cfg.max_gap,
                                         self.cfg.impact_angle, self.cfg.min_impact_gap)
        if not impacts:
            return []

        base_frame = self.total_frames - n
        times = list(self.timestamps)
        events: list[dict[str, Any]] = []
        # detect_impacts precisa dos pontos posteriores; evita publicar evento ainda instável.
        stable_limit = n - 4
        for hit in impacts:
            local_t = float(hit["t"])
            absolute_frame = base_frame + local_t
            if local_t > stable_limit or absolute_frame <= self.last_emitted_frame + max(2, self.fps * 0.15):
                continue
            if hit.get("floor", False):
                continue
            x, y = float(hit["x"]), float(hit["y"])
            q = tracker.quadrant_of(x / self.process_width, y / self.process_height,
                                    self.area, self.cfg.grid_cols, self.cfg.grid_rows)
            # Timestamp interpolado entre os quadros adjacentes ao instante subquadro.
            idx = max(0, min(n - 1, int(round(local_t))))
            end_ms = times[idx]
            start_idx = max(0, idx - int(self.fps * 0.65))
            start_ms = times[start_idx]
            col = (q - 1) % self.cfg.grid_cols if q else -1
            row = (q - 1) // self.cfg.grid_cols if q else -1
            events.append({
                "type": "ball_flight",
                "start_epoch_ms": int(start_ms),
                "end_epoch_ms": int(end_ms),
                "wall_configured": True,
                "wall_hit": q is not None,
                "wall_grid": {"cols": self.cfg.grid_cols, "rows": self.cfg.grid_rows},
                "impact": {
                    "col": col,
                    "row": row,
                    "quadrant": int(q or 0),
                    "x": round(x * self.scale_x, 1),
                    "y": round(y * self.scale_y, 1),
                    "x_normalized": round(x / self.process_width, 5),
                    "y_normalized": round(y / self.process_height, 5),
                    "angle": round(float(hit.get("angle", 0.0)), 1),
                    "kind": str(hit.get("kind", "impacto")),
                },
                # Não há calibração física distância/escala na configuração enviada.
                # Por isso, velocidade em km/h permanece explicitamente indisponível (0).
                "max_speed_kmh": 0.0,
                "average_speed_kmh": 0.0,
            })
            self.last_emitted_frame = int(absolute_frame)
        return events


class SmartTennisVisionService:
    def __init__(self, camera_index: int, http_port: int, discovery_port: int, token: str,
                 roi: str = "", wall_area: str = "", tracker_options: dict[str, Any] | None = None):
        self.camera_index = camera_index
        self.http_port = http_port
        self.discovery_port = discovery_port
        self.roi = roi.strip()
        self.wall_area = wall_area.strip()
        self.tracker_options = dict(tracker_options or {})
        # Valida os parâmetros antes de iniciar as threads para que um ROI inválido
        # não interrompa silenciosamente a leitura contínua da webcam.
        validation_options = dict(self.tracker_options)
        validation_options.update(roi=self.roi, area=self.wall_area, grid_cols=3, grid_rows=2, impact_mode="parede")
        validation = tracker.Config(**validation_options)
        validation.validate()
        tracker.parse_polygon(validation.roi, 960, 540)
        self.token = token or secrets.token_urlsafe(24)
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.camera_active = False
        self.camera_width = 0
        self.camera_height = 0
        self.camera_fps = 0.0
        self.current_session: dict[str, Any] | None = None
        self.live_tracker: LiveImpactTracker | None = None
        self.events: deque[dict[str, Any]] = deque(maxlen=600)
        self.next_event_id = 1
        self.camera_thread: threading.Thread | None = None
        self.discovery_thread: threading.Thread | None = None
        self.http_server: ThreadingHTTPServer | None = None
        self._stop_lock = threading.Lock()
        self._stop_started = False

    def start(self):
        self.camera_thread = threading.Thread(target=self._camera_loop, name="webcam", daemon=True)
        self.camera_thread.start()
        self.discovery_thread = threading.Thread(target=self._discovery_loop, name="udp-discovery", daemon=True)
        self.discovery_thread.start()
        handler = self._make_handler()
        self.http_server = ThreadingHTTPServer(("0.0.0.0", self.http_port), handler)
        self.http_server.daemon_threads = True
        print(f"[SmartTennis] API local: http://0.0.0.0:{self.http_port}")
        print(f"[SmartTennis] Descoberta UDP: porta {self.discovery_port}")
        print("[SmartTennis] Webcam será aberta automaticamente. Deixe este processo em execução.")
        print("[SmartTennis] A câmera e o computador devem estar na mesma rede local do celular.")
        self.http_server.serve_forever(poll_interval=0.25)

    def stop(self):
        # Idempotente: o sinal inicia o encerramento em outra thread e o finally
        # também chama stop depois que serve_forever termina.
        with self._stop_lock:
            if self._stop_started:
                return
            self._stop_started = True
        self.stop_event.set()
        if self.http_server:
            self.http_server.shutdown()
            self.http_server.server_close()

    def status(self):
        with self.lock:
            return {
                "ok": True,
                "service": SERVICE_NAME,
                "camera_active": self.camera_active,
                "model_ready": True,
                "camera_index": self.camera_index,
                "width": self.camera_width,
                "height": self.camera_height,
                "fps": round(self.camera_fps, 2),
                "wall": {"cols": 3, "rows": 2, "area": self.wall_area or "0,0,1,1"},
                "session_active": self.current_session is not None,
                "server_epoch_ms": int(time.time() * 1000),
            }

    def start_session(self, payload: dict[str, Any]) -> tuple[bool, str, int]:
        session_id = str(payload.get("sessionId", "")).strip()
        if not session_id:
            return False, "sessionId é obrigatório.", 400
        with self.lock:
            if not self.camera_active:
                return False, "A webcam ainda não está disponível no computador.", 409
            self.current_session = {
                "session_id": session_id,
                "challenge_id": str(payload.get("challengeId", ""))[:120],
                "camera_mode": str(payload.get("cameraMode", "IMPACTOS"))[:40],
                "started_epoch_ms": int(time.time() * 1000),
            }
            self.live_tracker = None  # reinicia a trajetória para esta missão
            cursor = self.next_event_id - 1
        return True, "Sessão de câmera iniciada.", cursor

    def stop_session(self, session_id: str) -> bool:
        with self.lock:
            if self.current_session and self.current_session["session_id"] == session_id:
                self.current_session = None
                self.live_tracker = None
                return True
        return False

    def publish_events(self, events: list[dict[str, Any]]):
        if not events:
            return
        with self.lock:
            if not self.current_session:
                return
            session_id = self.current_session["session_id"]
            for event in events:
                event["event_id"] = self.next_event_id
                event["session_id"] = session_id
                self.next_event_id += 1
                self.events.append(event)

    def read_events(self, after: int) -> list[dict[str, Any]]:
        with self.lock:
            return [dict(event) for event in self.events if int(event.get("event_id", 0)) > after]

    def _camera_loop(self):
        while not self.stop_event.is_set():
            cap = cv2.VideoCapture(self.camera_index)
            if not cap.isOpened():
                cap.release()
                with self.lock:
                    self.camera_active = False
                print(f"[SmartTennis] Webcam {self.camera_index} não abriu; nova tentativa em 3s.")
                self.stop_event.wait(3.0)
                continue

            cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
            cap.set(cv2.CAP_PROP_BUFFERSIZE, 2)
            width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
            with self.lock:
                self.camera_active = True
                self.camera_width = width
                self.camera_height = height
                self.camera_fps = fps if fps > 0 else 30.0
            print(f"[SmartTennis] Webcam ativa: {width}x{height} · {self.camera_fps:.1f} FPS")

            falhas = 0
            while not self.stop_event.is_set():
                ok, frame = cap.read()
                if not ok or frame is None or frame.size == 0:
                    falhas += 1
                    if falhas >= 30:
                        break
                    self.stop_event.wait(0.03)
                    continue
                falhas = 0
                timestamp = int(time.time() * 1000)
                with self.lock:
                    session = dict(self.current_session) if self.current_session else None
                    if session and self.live_tracker is None:
                        self.live_tracker = LiveImpactTracker(
                            frame.shape[1], frame.shape[0], self.camera_fps,
                            session.get("camera_mode", "IMPACTOS"), self.roi, self.wall_area
                        )
                    live = self.live_tracker if session else None
                if live is not None:
                    try:
                        self.publish_events(live.process(frame, timestamp))
                    except Exception as exc:
                        # Um quadro problemático nunca derruba a API nem a câmera.
                        print(f"[SmartTennis] Falha ao processar quadro: {exc}")
            cap.release()
            with self.lock:
                self.camera_active = False
                self.live_tracker = None
            if not self.stop_event.is_set():
                print("[SmartTennis] Stream da webcam interrompida; reconectando…")
                self.stop_event.wait(1.0)

    def _discovery_loop(self):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.bind(("0.0.0.0", self.discovery_port))
                sock.settimeout(0.5)
                while not self.stop_event.is_set():
                    try:
                        packet, address = sock.recvfrom(1024)
                    except socket.timeout:
                        continue
                    if packet.strip() != DISCOVERY_REQUEST:
                        continue
                    response = json.dumps({
                        "service": SERVICE_NAME,
                        "version": 1,
                        "port": self.http_port,
                        "token": self.token,
                    }).encode("utf-8")
                    sock.sendto(response, address)
        except OSError as exc:
            print(f"[SmartTennis] Não foi possível iniciar descoberta UDP: {exc}")

    def _make_handler(self):
        service = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "SmartTennisVision/1.0"

            def log_message(self, fmt, *args):
                # Logs de consulta frequente ficam silenciosos; erros são exibidos abaixo.
                if args and str(args[1] if len(args) > 1 else "").startswith("5"):
                    super().log_message(fmt, *args)

            def _json(self, status: int, payload: dict[str, Any]):
                raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(raw)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(raw)

            def _authorized(self) -> bool:
                supplied = self.headers.get("X-SmartTennis-Token", "")
                return secrets.compare_digest(supplied, service.token)

            def _body(self):
                length = min(int(self.headers.get("Content-Length", "0") or 0), 65536)
                if length <= 0:
                    return {}
                return json.loads(self.rfile.read(length).decode("utf-8"))

            def do_GET(self):
                parsed = urlparse(self.path)
                if parsed.path in ("/health", "/api/status"):
                    self._json(200, service.status())
                    return
                if not self._authorized():
                    self._json(401, {"ok": False, "message": "Token da estação inválido."})
                    return
                if parsed.path == "/api/events":
                    after = int(parse_qs(parsed.query).get("after", ["0"])[0])
                    self._json(200, {"ok": True, "events": service.read_events(after)})
                    return
                self._json(404, {"ok": False, "message": "Endpoint não encontrado."})

            def do_POST(self):
                if not self._authorized():
                    self._json(401, {"ok": False, "message": "Token da estação inválido."})
                    return
                try:
                    payload = self._body()
                except Exception:
                    self._json(400, {"ok": False, "message": "JSON inválido."})
                    return
                parsed = urlparse(self.path)
                if parsed.path == "/api/session/start":
                    ok, message, cursor = service.start_session(payload)
                    self._json(200 if ok else cursor, {"ok": ok, "message": message, "event_cursor": cursor})
                    return
                if parsed.path == "/api/session/stop":
                    stopped = service.stop_session(str(payload.get("sessionId", "")))
                    self._json(200, {"ok": True, "stopped": stopped})
                    return
                self._json(404, {"ok": False, "message": "Endpoint não encontrado."})

        return Handler


def _parse_triplet(value: str) -> tuple[int, int, int]:
    try:
        parts = tuple(int(item.strip()) for item in value.split(","))
        if len(parts) != 3 or any(item < 0 for item in parts):
            raise ValueError
        return parts
    except Exception as exc:
        raise argparse.ArgumentTypeError("use três inteiros separados por vírgula, por exemplo 20,65,55") from exc


def main():
    parser = argparse.ArgumentParser(description="Servidor headless de visão computacional SmartTennis")
    parser.add_argument("--camera", type=int, default=int(os.getenv("SMARTTENNIS_CAMERA", "0")),
                        help="índice da webcam OpenCV (padrão: 0)")
    parser.add_argument("--port", type=int, default=int(os.getenv("SMARTTENNIS_PORT", str(HTTP_PORT_DEFAULT))))
    parser.add_argument("--discovery-port", type=int,
                        default=int(os.getenv("SMARTTENNIS_DISCOVERY_PORT", str(DISCOVERY_PORT_DEFAULT))))
    parser.add_argument("--token", default=os.getenv("SMARTTENNIS_TOKEN", ""),
                        help="token opcional; padrão gera um token aleatório por inicialização")
    parser.add_argument("--wall-area", default=os.getenv("SMARTTENNIS_WALL_AREA", ""),
                        help="região da parede em coordenadas normalizadas x0,y0,x1,y1; vazio usa o quadro inteiro")
    parser.add_argument("--roi", default=os.getenv("SMARTTENNIS_ROI", ""),
                        help="polígono normalizado para limitar a busca da bola; vazio usa o quadro inteiro")
    # Parâmetros equivalentes aos controles avançados da versão de análise por vídeo.
    # Os padrões correspondem aos valores iniciais da interface antiga.
    parser.add_argument("--hsv-low", type=_parse_triplet, default=(20, 65, 55), metavar="H,S,V",
                        help="limite HSV mínimo (padrão: 20,65,55)")
    parser.add_argument("--hsv-high", type=_parse_triplet, default=(48, 255, 255), metavar="H,S,V",
                        help="limite HSV máximo (padrão: 48,255,255)")
    parser.add_argument("--min-area", type=float, default=4.0, help="área mínima do candidato (padrão: 4)")
    parser.add_argument("--max-area", type=float, default=900.0, help="área máxima do candidato (padrão: 900)")
    parser.add_argument("--min-circularity", type=float, default=0.18, help="circularidade mínima (padrão: 0.18)")
    parser.add_argument("--max-width", type=int, default=960, help="largura de processamento; 0 mantém original")
    parser.add_argument("--max-jump", type=float, default=180.0, help="salto máximo entre quadros (padrão: 180)")
    parser.add_argument("--max-gap", type=int, default=6, help="quadros tolerados sem detecção (padrão: 6)")
    parser.add_argument("--min-track-len", type=int, default=4, help="detecções mínimas por trajetória (padrão: 4)")
    parser.add_argument("--impact-angle", type=float, default=35.0, help="ângulo mínimo do impacto (padrão: 35)")
    parser.add_argument("--min-impact-gap", type=int, default=5, help="intervalo mínimo entre impactos (padrão: 5)")
    parser.add_argument("--max-predict", type=int, default=12, help="quadros de previsão balística (padrão: 12)")
    parser.add_argument("--min-speed", type=float, default=6.0, help="velocidade mínima em px/quadro (padrão: 6)")
    parser.add_argument("--max-bridge", type=int, default=None, help="maior lacuna preenchida; padrão max(max-gap, 20)")
    parser.add_argument("--no-motion", action="store_true", help="desliga o filtro de movimento")
    args = parser.parse_args()

    tracker_options = {
        "hsv_low": args.hsv_low,
        "hsv_high": args.hsv_high,
        "min_area": args.min_area,
        "max_area": args.max_area,
        "min_circularity": args.min_circularity,
        "max_width": args.max_width,
        "use_motion": not args.no_motion,
        "max_jump": args.max_jump,
        "max_gap": args.max_gap,
        "min_track_len": args.min_track_len,
        "impact_angle": args.impact_angle,
        "min_impact_gap": args.min_impact_gap,
        "max_predict": args.max_predict,
        "min_speed": args.min_speed,
        "max_bridge": args.max_bridge if args.max_bridge is not None else max(args.max_gap, 20),
    }
    service = SmartTennisVisionService(
        args.camera, args.port, args.discovery_port, args.token, args.roi, args.wall_area, tracker_options
    )

    def shutdown(*_):
        # HTTPServer.shutdown() não pode ser chamado pela própria thread de
        # serve_forever; o handler de sinal roda na thread principal.
        threading.Thread(target=service.stop, name="server-shutdown", daemon=True).start()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    try:
        service.start()
    except KeyboardInterrupt:
        pass
    finally:
        service.stop()


if __name__ == "__main__":
    main()
