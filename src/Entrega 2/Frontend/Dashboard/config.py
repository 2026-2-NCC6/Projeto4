# -*- coding: utf-8 -*-
"""
Configuração do backend do dashboard Smart Ace.

>>> Edite SOMENTE este arquivo (ou o .env) para apontar o dashboard para o seu backend. <<<
A interface não permite trocar de backend.

O dashboard NÃO se conecta ao banco de dados: ele fala apenas com a API do backend
(rotas /api/admin/*, somente leitura, protegidas pela chave DASHBOARD_API_KEY).
"""
import os

try:  # carrega o .env (se existir) ao lado deste arquivo
    from dotenv import load_dotenv
    load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))
except ImportError:
    pass

# De onde vêm os dados:
#   "api"  -> API do backend: visão GLOBAL (todos os usuários).
#   "demo" -> dados fictícios, sem backend (para testar/apresentar).
FONTE = "api"

# --- API do backend ---
API_URL = os.getenv("API_URL", "http://127.0.0.1:5001")
# Mesma chave definida em DASHBOARD_API_KEY no .env do backend (>= 32 caracteres).
# Não escreva a chave aqui: use o .env ou a variável de ambiente.
API_KEY = os.getenv("DASHBOARD_API_KEY", "")

# --- Segurança (opcional) ---
# Se preenchida, o dashboard pede esta senha antes de mostrar qualquer dado.
DASHBOARD_PASSWORD = os.getenv("DASHBOARD_PASSWORD", "")

# --- Desempenho ---
CACHE_TTL = 60  # segundos que os dados ficam em cache antes de nova consulta
