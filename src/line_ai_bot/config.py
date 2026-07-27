from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="APP_",
        extra="ignore",
    )

    app_name: str = "LINE AI Bot"
    environment: Literal["development", "test", "production"] = "development"
    database_url: str = "sqlite:///./line_ai_bot.db"
    auto_create_schema: bool = True

    encryption_key: str = "change-this-development-encryption-key"
    platform_admin_api_key: str = "change-this-platform-admin-key"

    line_api_enabled: bool = False
    line_api_base_url: str = "https://api.line.me"
    public_base_url: str = "http://localhost:8000"

    ai_provider: Literal["local", "openai"] = "local"
    ai_generation_model: str = "gpt-5.6-luna"
    ai_embedding_model: str = "text-embedding-3-small"
    ai_embedding_dimensions: int = 512
    ai_reasoning_effort: Literal["none", "low", "medium", "high"] = "none"
    ai_min_retrieval_score: float = 0.20
    ai_max_context_documents: int = 4
    ai_max_context_chars: int = 6000
    ai_chunk_size_chars: int = 1200
    ai_chunk_overlap_chars: int = 150
    ai_max_output_tokens: int = 500
    ai_timeout_seconds: float = 20.0

    openai_api_key: SecretStr | None = Field(default=None, validation_alias="OPENAI_API_KEY")
    openai_base_url: str | None = None

    @model_validator(mode="after")
    def reject_development_secrets_in_production(self) -> "Settings":
        if self.environment == "production":
            if self.encryption_key.startswith("change-this"):
                raise ValueError("APP_ENCRYPTION_KEY must be changed in production")
            if self.platform_admin_api_key.startswith("change-this"):
                raise ValueError("APP_PLATFORM_ADMIN_API_KEY must be changed in production")
            if self.database_url.startswith("sqlite"):
                raise ValueError("Production must use PostgreSQL, not SQLite")
        if self.ai_provider == "openai" and (
            self.openai_api_key is None or not self.openai_api_key.get_secret_value().strip()
        ):
            raise ValueError("OPENAI_API_KEY is required when APP_AI_PROVIDER=openai")
        if self.ai_embedding_dimensions < 64 or self.ai_embedding_dimensions > 3072:
            raise ValueError("APP_AI_EMBEDDING_DIMENSIONS must be between 64 and 3072")
        if not 0 <= self.ai_min_retrieval_score <= 1:
            raise ValueError("APP_AI_MIN_RETRIEVAL_SCORE must be between 0 and 1")
        if self.ai_chunk_size_chars < 200:
            raise ValueError("APP_AI_CHUNK_SIZE_CHARS must be at least 200")
        if self.ai_chunk_overlap_chars < 0:
            raise ValueError("APP_AI_CHUNK_OVERLAP_CHARS cannot be negative")
        if self.ai_chunk_overlap_chars >= self.ai_chunk_size_chars:
            raise ValueError("Chunk overlap must be smaller than chunk size")
        if self.ai_max_context_documents < 1 or self.ai_max_context_chars < 200:
            raise ValueError("AI context limits must be positive")
        if self.ai_max_output_tokens < 64:
            raise ValueError("APP_AI_MAX_OUTPUT_TOKENS must be at least 64")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
