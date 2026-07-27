from fastapi import APIRouter

from line_ai_bot import __version__
from line_ai_bot.config import get_settings
from line_ai_bot.schemas import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    settings = get_settings()
    return HealthResponse(status="ok", service=settings.app_name, version=__version__)
