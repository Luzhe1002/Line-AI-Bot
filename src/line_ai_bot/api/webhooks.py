import hashlib
import json

from fastapi import APIRouter, BackgroundTasks, Header, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from line_ai_bot.api.deps import AppSettings, DbSession
from line_ai_bot.models import LineChannel, LineEvent, Tenant
from line_ai_bot.schemas import WebhookAccepted
from line_ai_bot.services.line_webhook import process_line_event
from line_ai_bot.services.security import SecretCipher, verify_line_signature

router = APIRouter(prefix="/webhooks/line", tags=["line-webhook"])


@router.post("/{tenant_slug}", response_model=WebhookAccepted)
async def line_webhook(
    tenant_slug: str,
    request: Request,
    background_tasks: BackgroundTasks,
    db: DbSession,
    settings: AppSettings,
    x_line_signature: str | None = Header(default=None, alias="X-Line-Signature"),
) -> WebhookAccepted:
    tenant = db.scalar(select(Tenant).where(Tenant.slug == tenant_slug, Tenant.active.is_(True)))
    if tenant is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Tenant not found")
    channel = db.scalar(
        select(LineChannel).where(
            LineChannel.tenant_id == tenant.id,
            LineChannel.enabled.is_(True),
        )
    )
    if channel is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="LINE channel not found")

    raw_body = await request.body()
    if not x_line_signature:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing LINE signature",
        )
    channel_secret = SecretCipher(settings.encryption_key).decrypt(channel.channel_secret_encrypted)
    if not verify_line_signature(raw_body, x_line_signature, channel_secret):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid LINE signature",
        )

    try:
        payload = json.loads(raw_body)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid JSON") from exc

    events = payload.get("events") or []
    accepted_ids: list[str] = []
    duplicate = 0
    for index, event_payload in enumerate(events):
        event_fingerprint = hashlib.sha256(
            raw_body + b":" + str(index).encode("ascii")
        ).hexdigest()[:32]
        webhook_event_id = event_payload.get("webhookEventId") or event_fingerprint
        line_user_id = (event_payload.get("source") or {}).get("userId")
        record = LineEvent(
            tenant_id=tenant.id,
            webhook_event_id=webhook_event_id,
            event_type=event_payload.get("type", "unknown"),
            line_user_id=line_user_id,
            payload_json=json.dumps(event_payload, ensure_ascii=False),
        )
        try:
            with db.begin_nested():
                db.add(record)
                db.flush()
        except IntegrityError:
            duplicate += 1
        else:
            accepted_ids.append(record.id)
    db.commit()

    for event_id in accepted_ids:
        background_tasks.add_task(process_line_event, event_id)
    return WebhookAccepted(accepted=len(accepted_ids), duplicate=duplicate)
