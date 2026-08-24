import unittest
from auth import hash_password, verify_password, authenticate_user

class TestAuth(unittest.TestCase):

    def setUp(self):
        # Sample database with a hashed user password
        password = "SecretPassword123!"
        self.password_hash, self.salt = hash_password(password)
        self.user_db = {
            "alice": {
                "password_hash": self.password_hash,
                "salt": self.salt
            }
        }

    def test_hash_and_verify_password_success(self):
        pwd = "MySecurePassword"
        p_hash, salt = hash_password(pwd)
        self.assertTrue(verify_password(pwd, p_hash, salt))

    def test_verify_password_failure(self):
        pwd = "MySecurePassword"
        p_hash, salt = hash_password(pwd)
        self.assertFalse(verify_password("WrongPassword", p_hash, salt))

    def test_authenticate_user_success(self):
        self.assertTrue(authenticate_user("alice", "SecretPassword123!", self.user_db))

    def test_authenticate_user_wrong_password(self):
        self.assertFalse(authenticate_user("alice", "WrongPassword", self.user_db))

    def test_authenticate_user_nonexistent_user(self):
        self.assertFalse(authenticate_user("bob", "SecretPassword123!", self.user_db))

    def test_authenticate_user_empty_credentials(self):
        self.assertFalse(authenticate_user("", "SecretPassword123!", self.user_db))
        self.assertFalse(authenticate_user("alice", "", self.user_db))

if __name__ == "__main__":
    unittest.main()
