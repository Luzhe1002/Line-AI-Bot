from datetime import date
from typing import Annotated

from fastapi import APIRouter, HTTPException, Query, status
from sqlalchemy import select

from line_ai_bot.api.deps import DbSession, TenantAdmin
from line_ai_bot.models import Reservation
from line_ai_bot.schemas import (
    AvailabilityResponse,
    AvailabilitySlot,
    ReservationCreate,
    ReservationRead,
)
from line_ai_bot.services.booking import (
    BookingError,
    BookingManager,
    InvalidSlotError,
    SlotUnavailableError,
)

router = APIRouter(prefix="/api/v1/tenants/{tenant_id}", tags=["bookings"])


@router.get("/availability", response_model=AvailabilityResponse)
def get_availability(
    tenant: TenantAdmin,
    db: DbSession,
    service_id: Annotated[str, Query()],
    local_date: Annotated[date, Query()],
) -> AvailabilityResponse:
    try:
        slots = BookingManager(db).list_available_slots(tenant, service_id, local_date)
    except BookingError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    return AvailabilityResponse(
        tenant_id=tenant.id,
        service_id=service_id,
        local_date=local_date,
        timezone=tenant.timezone,
        slots=[AvailabilitySlot(starts_at=start, ends_at=end) for start, end in slots],
    )


@router.post("/reservations", response_model=ReservationRead, status_code=status.HTTP_201_CREATED)
def create_reservation(
    tenant: TenantAdmin,
    payload: ReservationCreate,
    db: DbSession,
) -> Reservation:
    try:
        return BookingManager(db).create_reservation(
            tenant,
            service_id=payload.service_id,
            line_user_id=payload.line_user_id,
            starts_at=payload.starts_at,
            customer_name=payload.customer_name,
            idempotency_key=payload.idempotency_key,
        )
    except SlotUnavailableError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except InvalidSlotError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=str(exc),
        ) from exc
    except BookingError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc


@router.get("/reservations", response_model=list[ReservationRead])
def list_reservations(
    tenant: TenantAdmin,
    db: DbSession,
    reservation_status: str | None = Query(default=None, alias="status"),
) -> list[Reservation]:
    query = select(Reservation).where(Reservation.tenant_id == tenant.id)
    if reservation_status:
        query = query.where(Reservation.status == reservation_status.upper())
    return list(db.scalars(query.order_by(Reservation.starts_at)).all())


@router.post("/reservations/{reservation_id}/cancel", response_model=ReservationRead)
def cancel_reservation(
    tenant: TenantAdmin,
    reservation_id: str,
    db: DbSession,
) -> Reservation:
    try:
        return BookingManager(db).cancel_reservation(tenant.id, reservation_id)
    except BookingError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
