from tests.conftest import provision_tenant


def test_tenant_provisioning_creates_safe_defaults(client, platform_headers):
    tenant, tenant_headers = provision_tenant(
        client,
        platform_headers,
        slug="merchant-one",
        name="商家一號",
    )

    assert tenant["name"] == "商家一號"
    assert tenant["slot_minutes"] == 60
    assert len(tenant["admin_api_key"]) >= 32

    unauthenticated = client.get(f"/api/v1/tenants/{tenant['id']}")
    assert unauthenticated.status_code == 401

    hours = client.get(
        f"/api/v1/tenants/{tenant['id']}/business-hours",
        headers=tenant_headers,
    )
    assert hours.status_code == 200
    assert [item["weekday"] for item in hours.json()] == [0, 1, 2, 3, 4]

    services = client.get(
        f"/api/v1/tenants/{tenant['id']}/booking-services",
        headers=tenant_headers,
    )
    assert services.status_code == 200
    assert services.json()[0]["name"] == "一般預約"


def test_duplicate_tenant_slug_is_rejected(client, platform_headers):
    provision_tenant(client, platform_headers, slug="same-slug")
    response = client.post(
        "/api/v1/tenants",
        headers=platform_headers,
        json={"name": "重複", "slug": "same-slug", "timezone": "Asia/Taipei"},
    )
    assert response.status_code == 409
