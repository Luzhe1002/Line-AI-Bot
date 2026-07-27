import json
from datetime import UTC, datetime

import httpx
from sqlalchemy.orm import Session

from line_ai_bot.config import Settings
from line_ai_bot.models import OutboxMessage


class LineDeliveryError(Exception):
    pass


class LineMessagingClient:
    def __init__(self, db: Session, settings: Settings) -> None:
        self.db = db
        self.settings = settings

    def reply(
        self,
        *,
        tenant_id: str,
        channel_access_token: str,
        reply_token: str,
        line_user_id: str | None,
        messages: list[dict],
    ) -> OutboxMessage:
        if not 1 <= len(messages) <= 5:
            raise ValueError("LINE reply must contain between one and five messages")

        outbox = OutboxMessage(
            tenant_id=tenant_id,
            line_user_id=line_user_id,
            reply_token=reply_token,
            delivery_type="REPLY",
            payload_json=json.dumps(messages, ensure_ascii=False),
            status="PENDING",
        )
        self.db.add(outbox)
        self.db.commit()
        self.db.refresh(outbox)

        if not self.settings.line_api_enabled:
            outbox.status = "SIMULATED"
            outbox.attempts = 1
            outbox.sent_at = datetime.now(UTC)
            self.db.commit()
            return outbox

        outbox.attempts += 1
        try:
            response = httpx.post(
                f"{self.settings.line_api_base_url.rstrip('/')}/v2/bot/message/reply",
                headers={
                    "Authorization": f"Bearer {channel_access_token}",
                    "Content-Type": "application/json",
                },
                json={"replyToken": reply_token, "messages": messages},
                timeout=10.0,
            )
            response.raise_for_status()
        except httpx.HTTPError as exc:
            outbox.status = "FAILED"
            outbox.error = str(exc)[:2000]
            self.db.commit()
            raise LineDeliveryError("LINE reply request failed") from exc

        outbox.status = "SENT"
        outbox.sent_at = datetime.now(UTC)
        self.db.commit()
        return outbox
