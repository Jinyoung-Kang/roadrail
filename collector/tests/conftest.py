from pathlib import Path

import pytest

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures"


@pytest.fixture
def fixtures_dir() -> Path:
    return FIXTURES
