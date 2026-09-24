"""설정 (환경변수 · .env). 키는 서버 프로세스에만 존재한다 (NFR-08)."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

ROOT = Path(__file__).resolve().parents[3]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=(ROOT / ".env",), extra="ignore")

    database_url: str = "postgresql://roadrail:roadrail@localhost:5462/roadrail"
    redis_url: str = "redis://localhost:6409/0"

    ex_api_key: str = ""
    data_go_kr_key: str = ""
    kakao_rest_api_key: str = ""

    quota_ex: int = 20000
    quota_korail: int = 9000
    quota_kma: int = 9000
    quota_airkorea: int = 450
    quota_kakao: int = 5000          # 카카오모빌리티 길찾기
    quota_kakao_local: int = 50000   # 카카오 로컬 검색 (공식 100,000/일)

    on_time_threshold_min: int = 5
    forecast_tau_min: int = 90
    rail_plan_days_ahead: int = 30
    scheduler_enabled: bool = True
    seed_path: Path = Field(default=ROOT / "seed" / "corridors.yaml")

    # 도로 품질 규칙 Q-v2 · H-v1 (roadrail/analytics/road_quality.py)
    q_hard_min_speed_kmh: float = 8.0  # 차량 수와 무관하게 이상치
    q_max_speed_kmh: float = 170.0
    q_free_min_speed_kmh: float = 60.0  # 가장 빠른 차량이 이 속도 이상이면 '흐르는' 슬롯
    q_mix_ratio: float = 2.5            # 평균 ≥ 최소 × 이 값 → 정차 혼입
    corridor_min_observed_ratio: float = 0.6

    def quota_limit(self, provider: str) -> int:
        return {
            "EX": self.quota_ex, "KORAIL": self.quota_korail, "KMA": self.quota_kma,
            "AIRKOREA": self.quota_airkorea, "KAKAO": self.quota_kakao,
            "KAKAO_LOCAL": self.quota_kakao_local,
        }[provider]

    def secrets(self) -> list[str]:
        return [s for s in (self.ex_api_key, self.data_go_kr_key, self.kakao_rest_api_key) if s]


@lru_cache
def settings() -> Settings:
    return Settings()
