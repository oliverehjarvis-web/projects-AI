"""
Entrypoint: pull the default Ollama model if not already present, then start uvicorn.
The model pull can take several minutes on first boot — this is expected.
"""
import os
import subprocess
import sys
import time
import httpx

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
DEFAULT_MODEL = os.environ.get("DEFAULT_MODEL", "gemma3:4b-it-q4_K_M")


def wait_for_ollama(timeout: int = 60) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            r = httpx.get(f"{OLLAMA_URL}/api/tags", timeout=2)
            if r.status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def model_present() -> bool:
    try:
        r = httpx.get(f"{OLLAMA_URL}/api/tags", timeout=5)
        models = [m["name"] for m in r.json().get("models", [])]
        return any(DEFAULT_MODEL in m for m in models)
    except Exception:
        return False


if __name__ == "__main__":
    print(f"[entrypoint] Waiting for Ollama at {OLLAMA_URL}…", flush=True)
    if not wait_for_ollama():
        print("[entrypoint] Ollama did not start in time — continuing anyway", flush=True)
    else:
        if not model_present():
            print(f"[entrypoint] Pulling model {DEFAULT_MODEL}…", flush=True)
            subprocess.run(["ollama", "pull", DEFAULT_MODEL], check=False)
        else:
            print(f"[entrypoint] Model {DEFAULT_MODEL} already present", flush=True)

    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, log_level="info")
