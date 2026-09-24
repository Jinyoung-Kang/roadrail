from roadrail.core.config import Settings
from roadrail.providers.base import CONCURRENCY


def test_every_provider_has_a_daily_quota():
    # 공급자를 추가하면 예산도 함께 정해야 한다 (없으면 첫 호출에서 KeyError 로 작업 실패)
    s = Settings()
    for p in CONCURRENCY:
        assert s.quota_limit(p) > 0, p
