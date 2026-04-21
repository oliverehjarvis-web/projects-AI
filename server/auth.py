from fastapi import HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from config import API_TOKEN

_bearer = HTTPBearer()


def require_auth(credentials: HTTPAuthorizationCredentials = Security(_bearer)) -> None:
    if credentials.credentials != API_TOKEN:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
