import json
from datetime import UTC, datetime
from urllib.parse import parse_qs, urlencode
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from line_ai_bot.config import Settings
from line_ai_bot.models import (
    BookingService,
    ConversationMessage,
    HandoffTicket,
    Reservation,
    Tenant,
)
from line_ai_bot.services.booking import BookingError, BookingManager, SlotUnavailableError
from line_ai_bot.services.intent import Intent, classify_intent
from line_ai_bot.services.knowledge import KnowledgeAnswerService


class ConversationService:
    def __init__(self, db: Session, settings: Settings) -> None:
        self.db = db
        self.settings = settings
        self.booking = BookingManager(db)
        self.knowledge = KnowledgeAnswerService(db, settings)

    def handle_text(self, tenant: Tenant, line_user_id: str, text: str) -> list[dict]:
        self._record_message(tenant.id, line_user_id, "INBOUND", "text", text)
        intent = classify_intent(text)

        if intent == Intent.HUMAN_HANDOFF:
            messages = self._create_handoff(tenant, line_user_id, "使用者要求人工客服")
        elif intent == Intent.BOOKING:
            messages = self._booking_options(tenant, line_user_id)
        elif intent == Intent.CANCEL_BOOKING:
            messages = self._cancellation_options(tenant, line_user_id)
        else:
            answer = self.knowledge.answer(
                tenant.id,
                text,
                tenant_name=tenant.name,
                line_user_id=line_user_id,
            )
            messages = [{"type": "text", "text": answer.answer[:5000]}]
            if not answer.grounded:
                messages[0]["quickReply"] = {
                    "items": [
                        {
                            "type": "action",
                            "action": {
                                "type": "message",
                                "label": "轉接人工客服",
                                "text": "我要人工客服",
                            },
                        }
                    ]
                }

        self._record_outbound_messages(tenant.id, line_user_id, messages)
        return messages

    def handle_postback(
        self,
        tenant: Tenant,
        line_user_id: str,
        data: str,
        webhook_event_id: str,
    ) -> list[dict]:
        self._record_message(tenant.id, line_user_id, "INBOUND", "postback", data)
        values = parse_qs(data, keep_blank_values=False)
        action = values.get("action", [""])[0]

        if action == "book":
            messages = self._book_from_postback(tenant, line_user_id, values, webhook_event_id)
        elif action == "cancel":
            messages = self._cancel_from_postback(tenant, line_user_id, values)
        elif action == "handoff":
            messages = self._create_handoff(tenant, line_user_id, "使用者點選人工客服")
        else:
            messages = [{"type": "text", "text": "無法辨識這個操作，請重新選擇。"}]

        self._record_outbound_messages(tenant.id, line_user_id, messages)
        return messages

    def _booking_options(self, tenant: Tenant, _line_user_id: str) -> list[dict]:
        services = self.db.scalars(
            select(BookingService).where(
                BookingService.tenant_id == tenant.id,
                BookingService.active.is_(True),
            )
        ).all()
        if not services:
            return [{"type": "text", "text": "商家尚未設定可預約服務，請聯絡人工客服。"}]

        service = services[0]
        slots = self.booking.next_available_slots(tenant, service.id, days=14, limit=10)
        if not slots:
            return [{"type": "text", "text": "未來兩週目前沒有可預約時段，請聯絡人工客服。"}]

        zone = ZoneInfo(tenant.timezone)
        items = []
        for start, _end in slots:
            local_start = start.astimezone(zone)
            iso_start = start.astimezone(UTC).isoformat().replace("+00:00", "Z")
            postback_data = urlencode(
                {"action": "book", "service_id": service.id, "starts_at": iso_start}
            )
            label = local_start.strftime("%m/%d %H:%M")
            items.append(
                {
                    "type": "action",
                    "action": {
                        "type": "postback",
                        "label": label,
                        "data": postback_data,
                        "displayText": f"我要預約 {label}",
                    },
                }
            )
        return [
            {
                "type": "text",
                "text": f"請選擇「{service.name}」的預約時段：",
                "quickReply": {"items": items},
            }
        ]

    def _cancellation_options(self, tenant: Tenant, line_user_id: str) -> list[dict]:
        now_naive = datetime.now(UTC).replace(tzinfo=None)
        reservations = self.db.scalars(
            select(Reservation)
            .where(
                Reservation.tenant_id == tenant.id,
                Reservation.line_user_id == line_user_id,
                Reservation.status == "CONFIRMED",
                Reservation.starts_at > now_naive,
            )
            .order_by(Reservation.starts_at)
            .limit(10)
        ).all()
        if not reservations:
            return [{"type": "text", "text": "目前查不到可取消的預約。"}]

        zone = ZoneInfo(tenant.timezone)
        items = []
        for reservation in reservations:
            start = reservation.starts_at.replace(tzinfo=UTC).astimezone(zone)
            label = start.strftime("取消 %m/%d %H:%M")
            items.append(
                {
                    "type": "action",
                    "action": {
                        "type": "postback",
                        "label": label,
                        "data": urlencode({"action": "cancel", "reservation_id": reservation.id}),
                        "displayText": label,
                    },
                }
            )
        return [
            {
                "type": "text",
                "text": "請選擇要取消的預約：",
                "quickReply": {"items": items},
            }
        ]

    def _book_from_postback(
        self,
        tenant: Tenant,
        line_user_id: str,
        values: dict[str, list[str]],
        webhook_event_id: str,
    ) -> list[dict]:
        try:
            service_id = values["service_id"][0]
            raw_start = values["starts_at"][0]
            starts_at = datetime.fromisoformat(raw_start.replace("Z", "+00:00"))
            reservation = self.booking.create_reservation(
                tenant,
                service_id=service_id,
                line_user_id=line_user_id,
                starts_at=starts_at,
                customer_name=None,
                idempotency_key=f"line:{webhook_event_id}",
            )
        except (KeyError, ValueError):
            return [{"type": "text", "text": "預約資料不完整，請重新選擇時段。"}]
        except SlotUnavailableError:
            return [{"type": "text", "text": "這個時段剛被預約，請輸入「預約」重新選擇。"}]
        except BookingError:
            return [{"type": "text", "text": "目前無法完成預約，請聯絡人工客服。"}]

        local_start = reservation.starts_at.replace(tzinfo=UTC).astimezone(
            ZoneInfo(tenant.timezone)
        )
        return [
            {
                "type": "text",
                "text": (
                    f"預約成功！時間：{local_start:%Y/%m/%d %H:%M}。\n預約編號：{reservation.id}"
                ),
            }
        ]

    def _cancel_from_postback(
        self,
        tenant: Tenant,
        line_user_id: str,
        values: dict[str, list[str]],
    ) -> list[dict]:
        try:
            reservation_id = values["reservation_id"][0]
            reservation = self.booking.cancel_reservation(
                tenant.id,
                reservation_id,
                line_user_id=line_user_id,
            )
        except (KeyError, BookingError):
            return [{"type": "text", "text": "找不到這筆預約，請聯絡人工客服。"}]
        return [{"type": "text", "text": f"預約已取消。預約編號：{reservation.id}"}]

    def _create_handoff(
        self,
        tenant: Tenant,
        line_user_id: str,
        reason: str,
    ) -> list[dict]:
        existing = self.db.scalar(
            select(HandoffTicket).where(
                HandoffTicket.tenant_id == tenant.id,
                HandoffTicket.line_user_id == line_user_id,
                HandoffTicket.status == "OPEN",
            )
        )
        if existing is None:
            self.db.add(
                HandoffTicket(
                    tenant_id=tenant.id,
                    line_user_id=line_user_id,
                    reason=reason,
                )
            )
            self.db.commit()
        return [{"type": "text", "text": "已通知人工客服，服務人員會儘快回覆您。"}]

    def _record_message(
        self,
        tenant_id: str,
        line_user_id: str,
        direction: str,
        message_type: str,
        content: str,
        metadata: dict | None = None,
    ) -> None:
        self.db.add(
            ConversationMessage(
                tenant_id=tenant_id,
                line_user_id=line_user_id,
                direction=direction,
                message_type=message_type,
                content=content,
                metadata_json=json.dumps(metadata, ensure_ascii=False) if metadata else None,
            )
        )
        self.db.commit()

    def _record_outbound_messages(
        self,
        tenant_id: str,
        line_user_id: str,
        messages: list[dict],
    ) -> None:
        for message in messages:
            self.db.add(
                ConversationMessage(
                    tenant_id=tenant_id,
                    line_user_id=line_user_id,
                    direction="OUTBOUND",
                    message_type=message.get("type", "unknown"),
                    content=message.get("text", json.dumps(message, ensure_ascii=False)),
                    metadata_json=json.dumps(message, ensure_ascii=False),
                )
            )
        self.db.commit()
