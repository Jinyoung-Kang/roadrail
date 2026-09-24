"""구조화 로그 (JSON 한 줄) + 키 마스킹 (FR-206, NFR-08/09)."""
from __future__ import annotations

import json
import logging
import sys
import urllib.parse

from .config import settings

MASK = "***"
SECRET_PARAM_NAMES = {"key", "servicekey", "authorization", "apikey"}


def mask_text(text: str) -> str:
    for s in settings().secrets():
        text = text.replace(s, MASK).replace(urllib.parse.quote(s, safe=""), MASK)
    return text


def mask_params(params: dict) -> dict:
    return {k: (MASK if k.lower() in SECRET_PARAM_NAMES else v) for k, v in params.items()}


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        extra = getattr(record, "fields", None)
        if extra:
            payload.update(extra)
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return mask_text(json.dumps(payload, ensure_ascii=False, default=str))


def setup_logging(level: str = "INFO") -> None:
    h = logging.StreamHandler(sys.stdout)
    h.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [h]
    root.setLevel(level)
    for noisy in ("httpx", "httpcore", "apscheduler.executors.default"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def log(logger: logging.Logger, msg: str, level: int = logging.INFO, **fields) -> None:
    logger.log(level, msg, extra={"fields": fields})
