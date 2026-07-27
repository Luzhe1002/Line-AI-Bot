from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI

from line_ai_bot.api.router import router
from line_ai_bot.config import get_settings
from line_ai_bot.database import engine
from line_ai_bot.models import Base


@asynccontextmanager
async def lifespan(_app: FastAPI):
    settings = get_settings()
    if settings.auto_create_schema:
        Base.metadata.create_all(bind=engine)
    yield


def create_app() -> FastAPI:
    settings = get_settings()
    application = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        lifespan=lifespan,
    )
    application.include_router(router)
    return application


app = create_app()


def run() -> None:
    uvicorn.run("line_ai_bot.main:app", host="0.0.0.0", port=8000, reload=False)


if __name__ == "__main__":
    run()
