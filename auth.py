import hashlib
import hmac
import os

def hash_password(password: str, salt: bytes = None) -> tuple[str, str]:
    """
    Hashes a password using PBKDF2 with SHA-256.
    Returns (hex_hash, hex_salt).
    """
    if salt is None:
        salt = os.urandom(16)
    
    key = hashlib.pbkdf2_hmac(
        'sha256',
        password.encode('utf-8'),
        salt,
        iterations=100000
    )
    return key.hex(), salt.hex()

def verify_password(password: str, stored_hash: str, stored_salt: str) -> bool:
    """
    Verifies a plain password against the stored hash and salt.
    Uses hmac.compare_digest for constant-time comparison to mitigate timing attacks.
    """
    salt_bytes = bytes.fromhex(stored_salt)
    calculated_hash, _ = hash_password(password, salt_bytes)
    return hmac.compare_digest(calculated_hash, stored_hash)

def authenticate_user(username: str, password: str, user_db: dict) -> bool:
    """
    Authenticates a user against a user database.
    user_db is expected to be a dictionary structured as:
    {
        "username": {
            "password_hash": "...",
            "salt": "..."
        }
    }
    """
    if not username or not password or not isinstance(user_db, dict):
        return False

    user_info = user_db.get(username)
    if not user_info:
        # Perform a dummy calculation to prevent timing attacks discerning valid vs invalid usernames
        hash_password("dummy_password", os.urandom(16))
        return False

    stored_hash = user_info.get("password_hash")
    stored_salt = user_info.get("salt")

    if not stored_hash or not stored_salt:
        return False

    return verify_password(password, stored_hash, stored_salt)
