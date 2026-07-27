from datetime import UTC, date, datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from line_ai_bot.models import BookingService, BusinessHour, Reservation, Tenant, utc_now


class BookingError(Exception):
    pass


class SlotUnavailableError(BookingError):
    pass


class InvalidSlotError(BookingError):
    pass


def as_utc_naive(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value
    return value.astimezone(UTC).replace(tzinfo=None)


def as_utc_aware(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


class BookingManager:
    def __init__(self, db: Session) -> None:
        self.db = db

    def list_available_slots(
        self,
        tenant: Tenant,
        service_id: str,
        local_date: date,
        *,
        now: datetime | None = None,
    ) -> list[tuple[datetime, datetime]]:
        service = self._get_service(tenant.id, service_id)
        if not service.active:
            return []

        hours = self.db.scalar(
            select(BusinessHour).where(
                BusinessHour.tenant_id == tenant.id,
                BusinessHour.weekday == local_date.weekday(),
                BusinessHour.active.is_(True),
            )
        )
        if hours is None:
            return []

        zone = ZoneInfo(tenant.timezone)
        local_open = datetime.combine(local_date, hours.open_time, tzinfo=zone)
        local_close = datetime.combine(local_date, hours.close_time, tzinfo=zone)
        interval = timedelta(minutes=tenant.slot_minutes)
        window_start = as_utc_naive(local_open)
        window_end = as_utc_naive(local_close)

        reserved_starts = set(
            self.db.scalars(
                select(Reservation.starts_at).where(
                    Reservation.tenant_id == tenant.id,
                    Reservation.status.in_(("HELD", "CONFIRMED")),
                    Reservation.starts_at >= window_start,
                    Reservation.starts_at < window_end,
                )
            ).all()
        )
        reserved_starts = {as_utc_naive(value) for value in reserved_starts}

        current_utc = as_utc_naive(now or utc_now())
        slots: list[tuple[datetime, datetime]] = []
        cursor = local_open
        while cursor + interval <= local_close:
            start_utc = as_utc_naive(cursor)
            end_utc = start_utc + interval
            if start_utc > current_utc and start_utc not in reserved_starts:
                slots.append((as_utc_aware(start_utc), as_utc_aware(end_utc)))
            cursor += interval
        return slots

    def next_available_slots(
        self,
        tenant: Tenant,
        service_id: str,
        *,
        days: int = 14,
        limit: int = 10,
        now: datetime | None = None,
    ) -> list[tuple[datetime, datetime]]:
        current = now or utc_now()
        local_today = current.astimezone(ZoneInfo(tenant.timezone)).date()
        result: list[tuple[datetime, datetime]] = []
        for offset in range(days):
            result.extend(
                self.list_available_slots(
                    tenant,
                    service_id,
                    local_today + timedelta(days=offset),
                    now=current,
                )
            )
            if len(result) >= limit:
                return result[:limit]
        return result

    def create_reservation(
        self,
        tenant: Tenant,
        *,
        service_id: str,
        line_user_id: str,
        starts_at: datetime,
        customer_name: str | None,
        idempotency_key: str,
    ) -> Reservation:
        existing = self.db.scalar(
            select(Reservation).where(
                Reservation.tenant_id == tenant.id,
                Reservation.idempotency_key == idempotency_key,
            )
        )
        if existing is not None:
            return existing

        self._get_service(tenant.id, service_id)
        start_utc = as_utc_naive(starts_at)
        self._validate_slot(tenant, start_utc)

        reservation = Reservation(
            tenant_id=tenant.id,
            service_id=service_id,
            line_user_id=line_user_id,
            customer_name=customer_name,
            starts_at=start_utc,
            ends_at=start_utc + timedelta(minutes=tenant.slot_minutes),
            status="CONFIRMED",
            idempotency_key=idempotency_key,
        )
        self.db.add(reservation)
        try:
            self.db.commit()
        except IntegrityError as exc:
            self.db.rollback()
            existing = self.db.scalar(
                select(Reservation).where(
                    Reservation.tenant_id == tenant.id,
                    Reservation.idempotency_key == idempotency_key,
                )
            )
            if existing is not None:
                return existing
            raise SlotUnavailableError("The selected slot is no longer available") from exc
        self.db.refresh(reservation)
        return reservation

    def cancel_reservation(
        self,
        tenant_id: str,
        reservation_id: str,
        *,
        line_user_id: str | None = None,
    ) -> Reservation:
        query = select(Reservation).where(
            Reservation.id == reservation_id,
            Reservation.tenant_id == tenant_id,
        )
        if line_user_id is not None:
            query = query.where(Reservation.line_user_id == line_user_id)
        reservation = self.db.scalar(query)
        if reservation is None:
            raise BookingError("Reservation not found")
        if reservation.status == "CANCELLED":
            return reservation
        reservation.status = "CANCELLED"
        reservation.cancelled_at = utc_now()
        self.db.commit()
        self.db.refresh(reservation)
        return reservation

    def _get_service(self, tenant_id: str, service_id: str) -> BookingService:
        service = self.db.scalar(
            select(BookingService).where(
                BookingService.id == service_id,
                BookingService.tenant_id == tenant_id,
            )
        )
        if service is None:
            raise BookingError("Booking service not found")
        return service

    def _validate_slot(self, tenant: Tenant, start_utc: datetime) -> None:
        if start_utc <= as_utc_naive(utc_now()):
            raise InvalidSlotError("Cannot reserve a past slot")

        zone = ZoneInfo(tenant.timezone)
        local_start = start_utc.replace(tzinfo=UTC).astimezone(zone)
        hours = self.db.scalar(
            select(BusinessHour).where(
                BusinessHour.tenant_id == tenant.id,
                BusinessHour.weekday == local_start.weekday(),
                BusinessHour.active.is_(True),
            )
        )
        if hours is None:
            raise InvalidSlotError("The business is closed on the selected day")

        local_end = local_start + timedelta(minutes=tenant.slot_minutes)
        day_open = datetime.combine(local_start.date(), hours.open_time, tzinfo=zone)
        day_close = datetime.combine(local_start.date(), hours.close_time, tzinfo=zone)
        if local_start < day_open or local_end > day_close:
            raise InvalidSlotError("The selected slot is outside business hours")

        minutes_from_open = int((local_start - day_open).total_seconds() // 60)
        if minutes_from_open % tenant.slot_minutes != 0:
            raise InvalidSlotError("The selected time is not aligned to a bookable slot")
