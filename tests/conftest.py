import os

import pytest
from fastapi.testclient import TestClient

os.environ["APP_ENVIRONMENT"] = "test"
os.environ["APP_DATABASE_URL"] = "sqlite:///./test_line_ai_bot.db"
os.environ["APP_AUTO_CREATE_SCHEMA"] = "true"
os.environ["APP_ENCRYPTION_KEY"] = "test-encryption-key"
os.environ["APP_PLATFORM_ADMIN_API_KEY"] = "test-platform-admin-key"
os.environ["APP_LINE_API_ENABLED"] = "false"

from line_ai_bot.database import engine  # noqa: E402
from line_ai_bot.main import app  # noqa: E402
from line_ai_bot.models import Base  # noqa: E402


@pytest.fixture(autouse=True)
def fresh_database():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


@pytest.fixture
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def platform_headers() -> dict[str, str]:
    return {"X-Platform-Admin-Key": "test-platform-admin-key"}


def provision_tenant(
    client: TestClient,
    platform_headers: dict[str, str],
    *,
    slug: str,
    name: str | None = None,
) -> tuple[dict, dict[str, str]]:
    response = client.post(
        "/api/v1/tenants",
        headers=platform_headers,
        json={
            "name": name or slug,
            "slug": slug,
            "timezone": "Asia/Taipei",
            "slot_minutes": 60,
        },
    )
    assert response.status_code == 201, response.text
    tenant = response.json()
    return tenant, {"X-Tenant-Api-Key": tenant["admin_api_key"]}
