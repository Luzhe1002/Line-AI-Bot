from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from tests.conftest import provision_tenant


def next_business_date() -> str:
    value = datetime.now(ZoneInfo("Asia/Taipei")).date() + timedelta(days=1)
    while value.weekday() >= 5:
        value += timedelta(days=1)
    return value.isoformat()


def first_service(client, tenant: dict, headers: dict[str, str]) -> dict:
    response = client.get(
        f"/api/v1/tenants/{tenant['id']}/booking-services",
        headers=headers,
    )
    assert response.status_code == 200
    return response.json()[0]


def first_slot(client, tenant: dict, service: dict, headers: dict[str, str]) -> str:
    response = client.get(
        f"/api/v1/tenants/{tenant['id']}/availability",
        headers=headers,
        params={"service_id": service["id"], "local_date": next_business_date()},
    )
    assert response.status_code == 200, response.text
    assert response.json()["slots"]
    return response.json()["slots"][0]["starts_at"]


def test_one_person_per_slot_and_idempotency(client, platform_headers):
    tenant, headers = provision_tenant(client, platform_headers, slug="booking-shop")
    service = first_service(client, tenant, headers)
    starts_at = first_slot(client, tenant, service, headers)

    payload = {
        "service_id": service["id"],
        "line_user_id": "U-user-one",
        "starts_at": starts_at,
        "customer_name": "王小明",
        "idempotency_key": "booking-request-0001",
    }
    first = client.post(
        f"/api/v1/tenants/{tenant['id']}/reservations",
        headers=headers,
        json=payload,
    )
    assert first.status_code == 201, first.text

    repeated = client.post(
        f"/api/v1/tenants/{tenant['id']}/reservations",
        headers=headers,
        json=payload,
    )
    assert repeated.status_code == 201
    assert repeated.json()["id"] == first.json()["id"]

    conflict_payload = payload | {
        "line_user_id": "U-user-two",
        "idempotency_key": "booking-request-0002",
    }
    conflict = client.post(
        f"/api/v1/tenants/{tenant['id']}/reservations",
        headers=headers,
        json=conflict_payload,
    )
    assert conflict.status_code == 409

    unavailable = client.get(
        f"/api/v1/tenants/{tenant['id']}/availability",
        headers=headers,
        params={"service_id": service["id"], "local_date": next_business_date()},
    )
    returned_starts = {slot["starts_at"] for slot in unavailable.json()["slots"]}
    assert starts_at not in returned_starts


def test_same_time_is_isolated_between_tenants(client, platform_headers):
    first_tenant, first_headers = provision_tenant(client, platform_headers, slug="tenant-a")
    second_tenant, second_headers = provision_tenant(client, platform_headers, slug="tenant-b")
    first_tenant_service = first_service(client, first_tenant, first_headers)
    second_tenant_service = first_service(client, second_tenant, second_headers)
    starts_at = first_slot(client, first_tenant, first_tenant_service, first_headers)

    for tenant, headers, service, user in (
        (first_tenant, first_headers, first_tenant_service, "U-a"),
        (second_tenant, second_headers, second_tenant_service, "U-b"),
    ):
        response = client.post(
            f"/api/v1/tenants/{tenant['id']}/reservations",
            headers=headers,
            json={
                "service_id": service["id"],
                "line_user_id": user,
                "starts_at": starts_at,
                "idempotency_key": f"request-{user}-0001",
            },
        )
        assert response.status_code == 201, response.text
