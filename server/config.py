import os

API_TOKEN: str = os.environ["API_TOKEN"]
OLLAMA_URL: str = os.environ.get("OLLAMA_URL", "http://localhost:11434")
DATA_DIR: str = os.environ.get("DATA_DIR", "/data")
DEFAULT_MODEL: str = os.environ.get("DEFAULT_MODEL", "gemma3:4b-it-q4_K_M")
DB_PATH: str = os.path.join(DATA_DIR, "projects_ai.db")
