from datetime import time
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from line_ai_bot.api.deps import AppSettings, DbSession, TenantAdmin, require_platform_admin
from line_ai_bot.models import BookingService, BusinessHour, Dataset, LineChannel, Tenant
from line_ai_bot.schemas import (
    BookingServiceCreate,
    BookingServiceRead,
    BusinessHourRead,
    BusinessHourUpsert,
    LineChannelRead,
    LineChannelUpsert,
    TenantCreate,
    TenantCreated,
    TenantRead,
)
from line_ai_bot.services.security import SecretCipher, generate_api_key, hash_api_key

router = APIRouter(prefix="/api/v1/tenants", tags=["tenants"])
PlatformAdmin = Annotated[None, Depends(require_platform_admin)]


@router.post("", response_model=TenantCreated, status_code=status.HTTP_201_CREATED)
def create_tenant(payload: TenantCreate, db: DbSession, _admin: PlatformAdmin) -> TenantCreated:
    if db.scalar(select(Tenant).where(Tenant.slug == payload.slug)) is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Tenant slug already exists",
        )

    api_key = generate_api_key()
    tenant = Tenant(
        name=payload.name,
        slug=payload.slug,
        timezone=payload.timezone,
        slot_minutes=payload.slot_minutes,
        admin_api_key_hash=hash_api_key(api_key),
    )
    db.add(tenant)
    db.flush()

    for weekday in range(5):
        db.add(
            BusinessHour(
                tenant_id=tenant.id,
                weekday=weekday,
                open_time=time(9, 0),
                close_time=time(18, 0),
                active=True,
            )
        )
    db.add(
        BookingService(
            tenant_id=tenant.id,
            name="一般預約",
            description="預設的一對一預約服務",
        )
    )
    db.add(Dataset(tenant_id=tenant.id, name="客服知識庫", version=1, status="DRAFT"))
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Tenant already exists",
        ) from exc
    db.refresh(tenant)
    return TenantCreated(
        id=tenant.id,
        name=tenant.name,
        slug=tenant.slug,
        timezone=tenant.timezone,
        slot_minutes=tenant.slot_minutes,
        active=tenant.active,
        created_at=tenant.created_at,
        admin_api_key=api_key,
    )


@router.get("", response_model=list[TenantRead])
def list_tenants(db: DbSession, _admin: PlatformAdmin) -> list[Tenant]:
    return list(db.scalars(select(Tenant).order_by(Tenant.created_at)).all())


@router.get("/{tenant_id}", response_model=TenantRead)
def get_tenant(tenant: TenantAdmin) -> Tenant:
    return tenant


@router.put("/{tenant_id}/line-channel", response_model=LineChannelRead)
def configure_line_channel(
    tenant: TenantAdmin,
    payload: LineChannelUpsert,
    db: DbSession,
    settings: AppSettings,
) -> LineChannelRead:
    cipher = SecretCipher(settings.encryption_key)
    channel = db.scalar(select(LineChannel).where(LineChannel.tenant_id == tenant.id))
    if channel is None:
        channel = LineChannel(
            tenant_id=tenant.id,
            channel_secret_encrypted=cipher.encrypt(payload.channel_secret),
            channel_access_token_encrypted=cipher.encrypt(payload.channel_access_token),
            enabled=payload.enabled,
        )
        db.add(channel)
    else:
        channel.channel_secret_encrypted = cipher.encrypt(payload.channel_secret)
        channel.channel_access_token_encrypted = cipher.encrypt(payload.channel_access_token)
        channel.enabled = payload.enabled
    db.commit()
    return LineChannelRead(
        tenant_id=tenant.id,
        configured=True,
        enabled=channel.enabled,
        webhook_url=f"{settings.public_base_url.rstrip('/')}/webhooks/line/{tenant.slug}",
    )


@router.get("/{tenant_id}/line-channel", response_model=LineChannelRead)
def get_line_channel(
    tenant: TenantAdmin,
    db: DbSession,
    settings: AppSettings,
) -> LineChannelRead:
    channel = db.scalar(select(LineChannel).where(LineChannel.tenant_id == tenant.id))
    return LineChannelRead(
        tenant_id=tenant.id,
        configured=channel is not None,
        enabled=bool(channel and channel.enabled),
        webhook_url=f"{settings.public_base_url.rstrip('/')}/webhooks/line/{tenant.slug}",
    )


@router.put("/{tenant_id}/business-hours", response_model=BusinessHourRead)
def upsert_business_hour(
    tenant: TenantAdmin,
    payload: BusinessHourUpsert,
    db: DbSession,
) -> BusinessHour:
    hours = db.scalar(
        select(BusinessHour).where(
            BusinessHour.tenant_id == tenant.id,
            BusinessHour.weekday == payload.weekday,
        )
    )
    if hours is None:
        hours = BusinessHour(
            tenant_id=tenant.id,
            weekday=payload.weekday,
            open_time=payload.open_time,
            close_time=payload.close_time,
            active=payload.active,
        )
        db.add(hours)
    else:
        hours.open_time = payload.open_time
        hours.close_time = payload.close_time
        hours.active = payload.active
    db.commit()
    db.refresh(hours)
    return hours


@router.get("/{tenant_id}/business-hours", response_model=list[BusinessHourRead])
def list_business_hours(tenant: TenantAdmin, db: DbSession) -> list[BusinessHour]:
    return list(
        db.scalars(
            select(BusinessHour)
            .where(BusinessHour.tenant_id == tenant.id)
            .order_by(BusinessHour.weekday)
        ).all()
    )


@router.post(
    "/{tenant_id}/booking-services",
    response_model=BookingServiceRead,
    status_code=status.HTTP_201_CREATED,
)
def create_booking_service(
    tenant: TenantAdmin,
    payload: BookingServiceCreate,
    db: DbSession,
) -> BookingService:
    service = BookingService(
        tenant_id=tenant.id,
        name=payload.name,
        description=payload.description,
    )
    db.add(service)
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A booking service with this name already exists",
        ) from exc
    db.refresh(service)
    return service


@router.get("/{tenant_id}/booking-services", response_model=list[BookingServiceRead])
def list_booking_services(tenant: TenantAdmin, db: DbSession) -> list[BookingService]:
    return list(
        db.scalars(
            select(BookingService)
            .where(BookingService.tenant_id == tenant.id)
            .order_by(BookingService.created_at)
        ).all()
    )
