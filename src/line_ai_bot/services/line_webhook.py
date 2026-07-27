import json

from sqlalchemy.orm import Session

from line_ai_bot.config import get_settings
from line_ai_bot.database import SessionLocal
from line_ai_bot.models import LineChannel, LineEvent, Tenant, utc_now
from line_ai_bot.services.conversation import ConversationService
from line_ai_bot.services.line_client import LineMessagingClient
from line_ai_bot.services.security import SecretCipher


def process_line_event(event_id: str) -> None:
    db = SessionLocal()
    try:
        _process_line_event(db, event_id)
    except Exception as exc:
        db.rollback()
        event = db.get(LineEvent, event_id)
        if event is not None:
            event.status = "FAILED"
            event.error = str(exc)[:4000]
            event.processed_at = utc_now()
            db.commit()
    finally:
        db.close()


def _process_line_event(db: Session, event_id: str) -> None:
    settings = get_settings()
    event = db.get(LineEvent, event_id)
    if event is None or event.status == "PROCESSED":
        return

    tenant = db.get(Tenant, event.tenant_id)
    if tenant is None or not tenant.active:
        raise ValueError("Tenant is unavailable")
    channel = db.query(LineChannel).filter(LineChannel.tenant_id == tenant.id).one_or_none()
    if channel is None or not channel.enabled:
        raise ValueError("LINE channel is unavailable")

    payload = json.loads(event.payload_json)
    source = payload.get("source") or {}
    line_user_id = source.get("userId")
    reply_token = payload.get("replyToken")
    if not line_user_id or not reply_token:
        event.status = "PROCESSED"
        event.processed_at = utc_now()
        db.commit()
        return

    conversation = ConversationService(db, settings)
    event_type = payload.get("type")
    if event_type == "message" and (payload.get("message") or {}).get("type") == "text":
        messages = conversation.handle_text(
            tenant,
            line_user_id,
            payload["message"].get("text", ""),
        )
    elif event_type == "postback":
        messages = conversation.handle_postback(
            tenant,
            line_user_id,
            (payload.get("postback") or {}).get("data", ""),
            event.webhook_event_id,
        )
    else:
        messages = [{"type": "text", "text": "目前僅支援文字訊息與預約操作。"}]

    cipher = SecretCipher(settings.encryption_key)
    token = cipher.decrypt(channel.channel_access_token_encrypted)
    LineMessagingClient(db, settings).reply(
        tenant_id=tenant.id,
        channel_access_token=token,
        reply_token=reply_token,
        line_user_id=line_user_id,
        messages=messages,
    )
    event.status = "PROCESSED"
    event.processed_at = utc_now()
    event.error = None
    db.commit()
