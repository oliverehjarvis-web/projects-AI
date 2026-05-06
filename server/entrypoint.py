"""
Entrypoint: pull the default Ollama model if not already present, then start uvicorn.
The model pull can take several minutes on first boot — this is expected.
"""
import logging
import os
import time
import httpx

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("entrypoint")

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
    """Pull the model via the Ollama HTTP API (streams progress to the logger)."""
    logger.info("Pulling %s via Ollama API…", DEFAULT_MODEL)
    with httpx.stream(
        "POST",
        f"{OLLAMA_URL}/api/pull",
        json={"name": DEFAULT_MODEL},
        timeout=None,
    ) as r:
        for line in r.iter_lines():
            if line:
                logger.info("ollama pull: %s", line)
    logger.info("Model pull complete.")


if __name__ == "__main__":
    logger.info("Waiting for Ollama at %s…", OLLAMA_URL)
    if not wait_for_ollama():
        logger.warning("Ollama did not start in time — continuing anyway")
    else:
        if not model_present():
            pull_model()
        else:
            logger.info("Model %s already present", DEFAULT_MODEL)

    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, log_level="info")
