import base64
import hashlib
import hmac
import json

from sqlalchemy import func, select

from line_ai_bot.database import SessionLocal
from line_ai_bot.models import LineEvent, OutboxMessage, Reservation
from tests.conftest import provision_tenant
from tests.test_knowledge import publish_document


def line_signature(raw_body: bytes, secret: str) -> str:
    digest = hmac.new(secret.encode(), raw_body, hashlib.sha256).digest()
    return base64.b64encode(digest).decode()


def test_webhook_signature_processing_and_deduplication(client, platform_headers):
    tenant, headers = provision_tenant(client, platform_headers, slug="line-shop")
    publish_document(
        client,
        tenant,
        headers,
        title="營業時間",
        content="客服資料顯示：平日上午九點至下午六點營業。",
    )
    secret = "line-channel-secret"
    configured = client.put(
        f"/api/v1/tenants/{tenant['id']}/line-channel",
        headers=headers,
        json={
            "channel_secret": secret,
            "channel_access_token": "line-channel-access-token",
            "enabled": True,
        },
    )
    assert configured.status_code == 200, configured.text

    payload = {
        "destination": "U-bot",
        "events": [
            {
                "type": "message",
                "webhookEventId": "01TESTWEBHOOK0000000000000001",
                "replyToken": "reply-token-one",
                "source": {"type": "user", "userId": "U-customer"},
                "message": {"type": "text", "id": "1001", "text": "營業時間？"},
            }
        ],
    }
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
    signature = line_signature(raw, secret)

    first = client.post(
        "/webhooks/line/line-shop",
        content=raw,
        headers={"Content-Type": "application/json", "X-Line-Signature": signature},
    )
    assert first.status_code == 200, first.text
    assert first.json() == {"accepted": 1, "duplicate": 0}

    with SessionLocal() as db:
        event = db.scalar(select(LineEvent))
        assert event.status == "PROCESSED"
        outbox = db.scalar(select(OutboxMessage))
        assert outbox.status == "SIMULATED"
        messages = json.loads(outbox.payload_json)
        assert "上午九點" in messages[0]["text"]

    duplicate = client.post(
        "/webhooks/line/line-shop",
        content=raw,
        headers={"Content-Type": "application/json", "X-Line-Signature": signature},
    )
    assert duplicate.status_code == 200
    assert duplicate.json() == {"accepted": 0, "duplicate": 1}

    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(OutboxMessage)) == 1

    invalid = client.post(
        "/webhooks/line/line-shop",
        content=raw,
        headers={"Content-Type": "application/json", "X-Line-Signature": "bad"},
    )
    assert invalid.status_code == 401


def test_line_quick_reply_can_create_a_reservation(client, platform_headers):
    tenant, headers = provision_tenant(client, platform_headers, slug="line-booking")
    secret = "line-booking-secret"
    configured = client.put(
        f"/api/v1/tenants/{tenant['id']}/line-channel",
        headers=headers,
        json={
            "channel_secret": secret,
            "channel_access_token": "line-booking-access-token",
            "enabled": True,
        },
    )
    assert configured.status_code == 200

    text_event = {
        "destination": "U-bot",
        "events": [
            {
                "type": "message",
                "webhookEventId": "01BOOKINGEVENT00000000000001",
                "replyToken": "reply-token-booking-options",
                "source": {"type": "user", "userId": "U-booking-customer"},
                "message": {"type": "text", "id": "2001", "text": "我要預約"},
            }
        ],
    }
    raw_text_event = json.dumps(
        text_event,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()
    options_response = client.post(
        "/webhooks/line/line-booking",
        content=raw_text_event,
        headers={
            "Content-Type": "application/json",
            "X-Line-Signature": line_signature(raw_text_event, secret),
        },
    )
    assert options_response.status_code == 200

    with SessionLocal() as db:
        options_outbox = db.scalar(select(OutboxMessage))
        options = json.loads(options_outbox.payload_json)
        postback_data = options[0]["quickReply"]["items"][0]["action"]["data"]

    postback_event = {
        "destination": "U-bot",
        "events": [
            {
                "type": "postback",
                "webhookEventId": "01BOOKINGEVENT00000000000002",
                "replyToken": "reply-token-booking-confirm",
                "source": {"type": "user", "userId": "U-booking-customer"},
                "postback": {"data": postback_data},
            }
        ],
    }
    raw_postback_event = json.dumps(
        postback_event,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode()
    booking_response = client.post(
        "/webhooks/line/line-booking",
        content=raw_postback_event,
        headers={
            "Content-Type": "application/json",
            "X-Line-Signature": line_signature(raw_postback_event, secret),
        },
    )
    assert booking_response.status_code == 200

    with SessionLocal() as db:
        reservation = db.scalar(select(Reservation))
        assert reservation.status == "CONFIRMED"
        assert reservation.line_user_id == "U-booking-customer"
        outboxes = db.scalars(select(OutboxMessage)).all()
        assert len(outboxes) == 2
        rendered_messages = [json.loads(item.payload_json) for item in outboxes]
        assert any("預約成功" in messages[0]["text"] for messages in rendered_messages)
