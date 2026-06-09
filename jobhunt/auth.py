"""Password hashing and signed session cookies, stdlib only."""
from __future__ import annotations

import base64
import hashlib
import hmac
import os
import time

from . import config

PBKDF2_ITERATIONS = 260_000
SESSION_MAX_AGE = 60 * 60 * 24 * 14  # 14 days


def hash_password(password: str) -> str:
    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode(), salt, PBKDF2_ITERATIONS
    )
    return f"pbkdf2${PBKDF2_ITERATIONS}${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, iterations, salt_hex, digest_hex = stored.split("$")
        digest = hashlib.pbkdf2_hmac(
            "sha256", password.encode(), bytes.fromhex(salt_hex), int(iterations)
        )
        return hmac.compare_digest(digest.hex(), digest_hex)
    except (ValueError, TypeError):
        return False


def _sign(payload: str) -> str:
    key = config.get_secret_key().encode()
    return hmac.new(key, payload.encode(), hashlib.sha256).hexdigest()


def make_session_token(user_id: int) -> str:
    payload = f"{user_id}.{int(time.time())}"
    encoded = base64.urlsafe_b64encode(payload.encode()).decode()
    return f"{encoded}.{_sign(payload)}"


def read_session_token(token: str) -> int | None:
    """Return the user id if the token is valid and unexpired, else None."""
    try:
        encoded, signature = token.rsplit(".", 1)
        payload = base64.urlsafe_b64decode(encoded.encode()).decode()
        if not hmac.compare_digest(signature, _sign(payload)):
            return None
        user_id_str, issued_str = payload.split(".")
        if time.time() - int(issued_str) > SESSION_MAX_AGE:
            return None
        return int(user_id_str)
    except (ValueError, TypeError):
        return None
