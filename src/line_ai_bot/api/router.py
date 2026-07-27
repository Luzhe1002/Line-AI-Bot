from fastapi import APIRouter

from line_ai_bot.api import bookings, health, knowledge, tenants, webhooks

router = APIRouter()
router.include_router(health.router)
router.include_router(tenants.router)
router.include_router(bookings.router)
router.include_router(knowledge.router)
router.include_router(webhooks.router)
