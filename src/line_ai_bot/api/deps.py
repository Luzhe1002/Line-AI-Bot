import hmac
from typing import Annotated

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy.orm import Session

from line_ai_bot.config import Settings, get_settings
from line_ai_bot.database import get_db
from line_ai_bot.models import Tenant
from line_ai_bot.services.security import verify_api_key

DbSession = Annotated[Session, Depends(get_db)]
AppSettings = Annotated[Settings, Depends(get_settings)]


def require_platform_admin(
    settings: AppSettings,
    x_platform_admin_key: Annotated[str | None, Header(alias="X-Platform-Admin-Key")] = None,
) -> None:
    if x_platform_admin_key is None or not hmac.compare_digest(
        x_platform_admin_key, settings.platform_admin_api_key
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid platform admin API key",
        )


def require_tenant_admin(
    tenant_id: str,
    db: DbSession,
    x_tenant_api_key: Annotated[str | None, Header(alias="X-Tenant-Api-Key")] = None,
) -> Tenant:
    tenant = db.get(Tenant, tenant_id)
    if tenant is None or not tenant.active:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found")
    if x_tenant_api_key is None or not verify_api_key(x_tenant_api_key, tenant.admin_api_key_hash):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid tenant API key",
        )
    return tenant


TenantAdmin = Annotated[Tenant, Depends(require_tenant_admin)]
