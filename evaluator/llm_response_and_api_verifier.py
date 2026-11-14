"""
Deterministic Verifier for API Responses & LLM-Generated SQL/JSON Outputs.
Designed for RLHF Side-by-Side (SxS) code auditing and edge-case testing.
"""

import re
import unittest
from typing import Dict, Any, Tuple


VALID_TIERS = {"BASIC", "STANDARD", "PREMIUM_4K"}
FORBIDDEN_SQL_PATTERNS = re.compile(r"\b(DROP|TRUNCATE|DELETE\s+FROM\s+\w+\s*;)\b", re.IGNORECASE)


def verify_billing_response(payload: Dict[str, Any]) -> Tuple[bool, str]:
    """Audits billing response payloads for schema compliance and type safety."""
    if not isinstance(payload, dict):
        return False, "Payload must be a JSON dictionary."

    required_fields = {"transactionId", "accountId", "amount", "status", "idempotentReplay"}
    missing = required_fields - set(payload.keys())
    if missing:
        return False, f"Missing required schema keys: {sorted(missing)}"

    amount = payload.get("amount")
    if isinstance(amount, bool) or not isinstance(amount, (int, float)) or amount <= 0:
        return False, "Invalid 'amount': must be a strictly positive number."

    if not isinstance(payload.get("idempotentReplay"), bool):
        return False, "Field 'idempotentReplay' must be a strict boolean."

    return True, "PASS"


def audit_llm_sql_query(sql_query: str) -> Tuple[bool, str]:
    """Evaluates LLM-generated SQL queries for read-safety and optimization standards."""
    if not isinstance(sql_query, str) or not sql_query.strip():
        return False, "Empty SQL query."

    if FORBIDDEN_SQL_PATTERNS.search(sql_query):
        return False, "Unsafe destructive SQL command detected."

    if "SELECT *" in sql_query.upper():
        return False, "Suboptimal query: avoid 'SELECT *' in production billing queries."

    return True, "PASS"


class TestDeterministicVerifier(unittest.TestCase):
    """Unit test suite verifying boundary conditions and LLM failure modes."""

    def test_valid_billing_payload(self) -> None:
        sample = {
            "transactionId": "tx-101",
            "accountId": "ACC-99",
            "amount": 14.99,
            "status": "CAPTURED",
            "idempotentReplay": False,
        }
        passed, reason = verify_billing_response(sample)
        self.assertTrue(passed)
        self.assertEqual(reason, "PASS")

    def test_reject_boolean_amount_trap(self) -> None:
        sample = {
            "transactionId": "tx-102",
            "accountId": "ACC-99",
            "amount": True,  # Edge case: bool is a subclass of int in Python
            "status": "CAPTURED",
            "idempotentReplay": False,
        }
        passed, reason = verify_billing_response(sample)
        self.assertFalse(passed)
        self.assertIn("strictly positive number", reason)

    def test_audit_llm_sql_rejects_select_star(self) -> None:
        bad_sql = "SELECT * FROM billing_transactions WHERE account_id = 'ACC-99';"
        passed, reason = audit_llm_sql_query(bad_sql)
        self.assertFalse(passed)
        self.assertIn("avoid 'SELECT *'", reason)


if __name__ == "__main__":
    unittest.main(verbosity=2)
