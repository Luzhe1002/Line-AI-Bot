from datetime import UTC, date, datetime, time
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    field_serializer,
    field_validator,
    model_validator,
)


class ApiModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)


class HealthResponse(ApiModel):
    status: str
    service: str
    version: str


class TenantCreate(ApiModel):
    name: str = Field(min_length=1, max_length=160)
    slug: str = Field(pattern=r"^[a-z0-9][a-z0-9-]{2,79}$")
    timezone: str = "Asia/Taipei"
    slot_minutes: int = 60

    @field_validator("timezone")
    @classmethod
    def validate_timezone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("Unknown IANA timezone") from exc
        return value

    @field_validator("slot_minutes")
    @classmethod
    def validate_slot_minutes(cls, value: int) -> int:
        if value not in {15, 20, 30, 45, 60, 90, 120}:
            raise ValueError("slot_minutes must be one of 15, 20, 30, 45, 60, 90, 120")
        return value


class TenantRead(ApiModel):
    id: str
    name: str
    slug: str
    timezone: str
    slot_minutes: int
    active: bool
    created_at: datetime


class TenantCreated(TenantRead):
    admin_api_key: str


class LineChannelUpsert(ApiModel):
    channel_secret: str = Field(min_length=8)
    channel_access_token: str = Field(min_length=8)
    enabled: bool = True


class LineChannelRead(ApiModel):
    tenant_id: str
    configured: bool
    enabled: bool
    webhook_url: str


class BusinessHourUpsert(ApiModel):
    weekday: int = Field(ge=0, le=6, description="Monday=0, Sunday=6")
    open_time: time
    close_time: time
    active: bool = True

    @model_validator(mode="after")
    def validate_range(self) -> "BusinessHourUpsert":
        if self.open_time >= self.close_time:
            raise ValueError("open_time must be before close_time")
        return self


class BusinessHourRead(ApiModel):
    id: str
    tenant_id: str
    weekday: int
    open_time: time
    close_time: time
    active: bool


class BookingServiceCreate(ApiModel):
    name: str = Field(min_length=1, max_length=160)
    description: str | None = Field(default=None, max_length=2000)


class BookingServiceRead(ApiModel):
    id: str
    tenant_id: str
    name: str
    description: str | None
    active: bool


class AvailabilitySlot(ApiModel):
    starts_at: datetime
    ends_at: datetime

    @field_serializer("starts_at", "ends_at")
    def serialize_utc(self, value: datetime) -> str:
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


class AvailabilityResponse(ApiModel):
    tenant_id: str
    service_id: str
    local_date: date
    timezone: str
    slots: list[AvailabilitySlot]


class ReservationCreate(ApiModel):
    service_id: str
    line_user_id: str = Field(min_length=1, max_length=64)
    starts_at: datetime
    customer_name: str | None = Field(default=None, max_length=160)
    idempotency_key: str = Field(min_length=8, max_length=128)

    @field_validator("starts_at")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("starts_at must include a timezone offset")
        return value


class ReservationRead(ApiModel):
    id: str
    tenant_id: str
    service_id: str
    line_user_id: str
    customer_name: str | None
    starts_at: datetime
    ends_at: datetime
    status: str
    idempotency_key: str
    created_at: datetime
    cancelled_at: datetime | None

    @field_serializer("starts_at", "ends_at", "created_at", "cancelled_at")
    def serialize_datetime(self, value: datetime | None) -> str | None:
        if value is None:
            return None
        if value.tzinfo is None:
            value = value.replace(tzinfo=UTC)
        return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


class DatasetCreate(ApiModel):
    name: str = Field(min_length=1, max_length=160)
    version: int = Field(default=1, ge=1)


class DatasetRead(ApiModel):
    id: str
    tenant_id: str
    name: str
    version: int
    status: str
    created_at: datetime
    published_at: datetime | None


class KnowledgeDocumentCreate(ApiModel):
    title: str = Field(min_length=1, max_length=240)
    content: str = Field(min_length=1, max_length=100_000)
    source_url: str | None = Field(default=None, max_length=1024)


class KnowledgeDocumentRead(ApiModel):
    id: str
    tenant_id: str
    dataset_id: str
    title: str
    content: str
    source_url: str | None
    active: bool
    index_status: str
    index_error: str | None
    indexed_at: datetime | None


class Citation(ApiModel):
    document_id: str
    chunk_id: str | None = None
    title: str
    source_url: str | None = None
    score: float
    snippet: str | None = None


class AnswerRequest(ApiModel):
    question: str = Field(min_length=1, max_length=4000)
    line_user_id: str | None = Field(default=None, max_length=64)


class AnswerResponse(ApiModel):
    answer: str
    confidence: float = Field(ge=0, le=1)
    grounded: bool
    citations: list[Citation]
    dataset_id: str | None = None
    provider: str = "local"
    model: str = "extractive-v1"
    retrieval_method: str = "vector"


class ReindexResponse(ApiModel):
    indexed: int
    failed: int
    errors: list[str]


class WebhookAccepted(ApiModel):
    accepted: int
    duplicate: int
