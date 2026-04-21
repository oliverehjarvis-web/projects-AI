"""
Entrypoint: pull the default Ollama model if not already present, then start uvicorn.
The model pull can take several minutes on first boot — this is expected.
"""
import os
import time
import httpx

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
DEFAULT_MODEL = os.environ.get("DEFAULT_MODEL", "gemma4:2b")


def wait_for_ollama(timeout: int = 120) -> bool:
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


def pull_model() -> None:
    """Pull the model via the Ollama HTTP API (streams progress to stdout)."""
    print(f"[entrypoint] Pulling {DEFAULT_MODEL} via Ollama API…", flush=True)
    with httpx.stream(
        "POST",
        f"{OLLAMA_URL}/api/pull",
        json={"name": DEFAULT_MODEL},
        timeout=None,
    ) as r:
        for line in r.iter_lines():
            if line:
                print(f"[ollama] {line}", flush=True)
    print("[entrypoint] Model pull complete.", flush=True)


if __name__ == "__main__":
    print(f"[entrypoint] Waiting for Ollama at {OLLAMA_URL}…", flush=True)
    if not wait_for_ollama():
        print("[entrypoint] Ollama did not start in time — continuing anyway", flush=True)
    else:
        if not model_present():
            pull_model()
        else:
            print(f"[entrypoint] Model {DEFAULT_MODEL} already present", flush=True)

    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, log_level="info")
