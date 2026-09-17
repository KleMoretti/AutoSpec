"""Durable, conservative experiment reservations shared across collector restarts."""
from __future__ import annotations

import sqlite3
from contextlib import closing
from decimal import Decimal, ROUND_CEILING
from pathlib import Path


DEFAULT_LEDGER_PATH = Path(__file__).resolve().parents[1] / "target" / "evaluation-budgets.sqlite3"


def money_units(value: float) -> int:
    amount = Decimal(str(value))
    if not amount.is_finite() or amount <= 0:
        raise ValueError("budget amounts must be finite and positive")
    return int((amount * 1_000_000).to_integral_value(rounding=ROUND_CEILING))


class BudgetLedger:
    """Reserve before creating a run; unknown outcomes never release money.

    The authorization ID belongs to the user's budget approval, not an experiment
    ID or output folder. Keep this database for the entire approved round.
    """

    def __init__(self, path: Path, authorization_id: str, currency: str, cap: float):
        self.path, self.authorization_id = path, authorization_id
        self.cap = money_units(cap)
        path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(path, timeout=30)) as connection, connection:
            connection.execute("CREATE TABLE IF NOT EXISTS authorizations "
                               "(id TEXT PRIMARY KEY, currency TEXT NOT NULL, cap INTEGER NOT NULL)")
            connection.execute("CREATE TABLE IF NOT EXISTS reservations "
                               "(authorization_id TEXT NOT NULL, run_key TEXT NOT NULL, amount INTEGER NOT NULL, "
                               "PRIMARY KEY (authorization_id, run_key))")
            connection.execute("INSERT OR IGNORE INTO authorizations VALUES (?, ?, ?)",
                               (authorization_id, currency, self.cap))
            row = connection.execute("SELECT currency, cap FROM authorizations WHERE id = ?",
                                     (authorization_id,)).fetchone()
            if row != (currency, self.cap):
                raise ValueError("existing authorization currency/cap cannot be changed")

    def reserve(self, run_key: str, amount: float) -> bool:
        units = money_units(amount)
        with closing(sqlite3.connect(self.path, timeout=30)) as connection, connection:
            connection.execute("BEGIN IMMEDIATE")
            duplicate = connection.execute(
                "SELECT 1 FROM reservations WHERE authorization_id = ? AND run_key = ?",
                (self.authorization_id, run_key)).fetchone()
            if duplicate:
                raise ValueError("run already reserved; inspect its journal before resuming")
            used = connection.execute(
                "SELECT COALESCE(SUM(amount), 0) FROM reservations WHERE authorization_id = ?",
                (self.authorization_id,)).fetchone()[0]
            if used + units > self.cap:
                return False
            connection.execute("INSERT INTO reservations VALUES (?, ?, ?)",
                               (self.authorization_id, run_key, units))
            return True
