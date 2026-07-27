from enum import StrEnum


class Intent(StrEnum):
    BOOKING = "BOOKING"
    CANCEL_BOOKING = "CANCEL_BOOKING"
    HUMAN_HANDOFF = "HUMAN_HANDOFF"
    KNOWLEDGE = "KNOWLEDGE"


def classify_intent(text: str) -> Intent:
    normalized = text.strip().lower()
    if any(term in normalized for term in ("取消預約", "取消訂位", "cancel booking", "取消")):
        return Intent.CANCEL_BOOKING
    if any(term in normalized for term in ("真人", "人工", "客服人員", "專人", "human")):
        return Intent.HUMAN_HANDOFF
    if any(term in normalized for term in ("預約", "訂位", "預訂", "booking", "book")):
        return Intent.BOOKING
    return Intent.KNOWLEDGE
