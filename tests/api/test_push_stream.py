"""自托管实时通知（cloud-push.md §4.1）的端到端测试。

不连 MovieClaw Cloud、不配任何中继：Android App 登记成实时通知设备，推送写进它的发件箱，
从 ``GET /push/me/stream`` 补发 + 实时送出，``POST /push/me/ack`` 之后不再补发。

TestClient 会等响应体结束才返回，所以把单条连接的最长时间缩到一两秒：连接到点正常结束，
正好验证「补发 → 实时 → 心跳 → 收尾」整条链路。
"""

from __future__ import annotations

import json
import threading
import time
from collections.abc import Callable
from datetime import timedelta

import httpx
import pytest
from fastapi.testclient import TestClient

from movieclaw_api.core.config import get_settings
from movieclaw_api.services.auth import reset_auth_state
from movieclaw_api.settings.store import reset_setting_store
from movieclaw_db.crypto import reset_secret_box

_AUTH = "/api/v1/auth"
_ADMIN = {"username": "admin", "password": "s3cret-pass"}
_MEMBER = {"username": "family", "password": "family-pass-1"}
_STREAM = "/api/v1/push/me/stream"


@pytest.fixture
def client(tmp_path, monkeypatch):  # type: ignore[no-untyped-def]
    monkeypatch.setenv("DATABASE_URL", f"sqlite+aiosqlite:///{tmp_path / 'push.db'}")
    monkeypatch.setenv("SECRET_KEY_FILE", str(tmp_path / ".secret_key"))
    monkeypatch.setenv("SCHEDULER_ENABLED", "false")
    get_settings.cache_clear()
    reset_setting_store()
    reset_secret_box()
    reset_auth_state()
    from movieclaw_api.services.push import channels, events, me, outbox

    channels.reset_runtime()
    me.reset_state()
    events.reset_state()
    monkeypatch.setattr(outbox, "MAX_CONNECTION_S", 1.5)
    monkeypatch.setattr(outbox, "HEARTBEAT_S", 0.4)
    monkeypatch.setattr(outbox, "POLL_S", 0.1)
    from movieclaw_api.app import create_app

    with TestClient(create_app()) as c:
        c.post(f"{_AUTH}/bootstrap", json=_ADMIN)
        c.post(f"{_AUTH}/login", json=_ADMIN)
        yield c
    events.reset_state()
    reset_setting_store()
    reset_secret_box()
    reset_auth_state()
    get_settings.cache_clear()


def _data(resp: httpx.Response) -> dict:
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


def _as_app(client: TestClient, bearer: str, method: str, url: str, **kwargs) -> httpx.Response:  # type: ignore[no-untyped-def]
    """以 App 的身份请求：只带设备令牌，不带浏览器的会话 Cookie。"""
    saved = dict(client.cookies)
    client.cookies.clear()
    headers = {**kwargs.pop("headers", {}), "Authorization": f"Bearer {bearer}"}
    try:
        return client.request(method, url, headers=headers, **kwargs)
    finally:
        for name, value in saved.items():
            client.cookies.set(name, value)


def _app_login(
    client: TestClient, who: dict, *, installation: str, name: str, kind: str = "android"
) -> str:
    saved = dict(client.cookies)
    client.cookies.clear()
    try:
        resp = client.post(
            f"{_AUTH}/device/login",
            json={**who, "client": {"kind": kind, "installation_id": installation, "name": name}},
        )
    finally:
        for cookie, value in saved.items():
            client.cookies.set(cookie, value)
    return _data(resp)["token"]


def _enable_stream(client: TestClient, bearer: str, *, on: bool = True) -> dict:
    return _data(
        _as_app(
            client,
            bearer,
            "PUT",
            "/api/v1/push/me/registration",
            json={"permission": "authorized", "stream": on},
        )
    )


def _call(client: TestClient, fn):  # type: ignore[no-untyped-def]
    assert client.portal is not None
    return client.portal.call(fn)


def _notify(client: TestClient, members: set[int], title: str, event: str = "imported") -> None:
    """在应用的事件循环里推一条（和业务代码一样走 notify）。"""
    from movieclaw_api.services.push.notify import AlertContent, notify

    async def build(_session, _member_id: int) -> AlertContent:  # type: ignore[no-untyped-def]
        return AlertContent(
            title=title, body="正文", open="/library/1/item/2", thread="t", level="passive"
        )

    async def run() -> None:
        notify(event, set(members), build, collapse=("item", title))

    _call(client, run)


def _rows(client: TestClient) -> list:
    from sqlmodel import select

    from movieclaw_db.engine import get_database
    from movieclaw_db.models import PushOutbox

    async def go() -> list:
        async with get_database().session() as session:
            return list(
                (await session.execute(select(PushOutbox).order_by(PushOutbox.id))).scalars()
            )

    return _call(client, go)


def _wait(predicate: Callable[[], bool], timeout: float = 8.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return
        time.sleep(0.05)
    raise AssertionError("等待超时")


def _parse(body: str) -> list[dict]:
    """把 SSE 文本拆成帧：注释行记成 ``{"comment": ...}``。"""
    frames: list[dict] = []
    for block in body.split("\n\n"):
        if not block.strip():
            continue
        frame: dict = {}
        for line in block.split("\n"):
            if line.startswith(":"):
                frame["comment"] = line[1:].strip()
            else:
                key, _, value = line.partition(": ")
                frame[key] = value
        frames.append(frame)
    return frames


def _notifications(frames: list[dict]) -> list[dict]:
    return [json.loads(f["data"]) for f in frames if f.get("event") == "notification"]


# ----------------------------------------------------------------------
# 登记
# ----------------------------------------------------------------------


def test_stream_registration_and_status(client: TestClient) -> None:
    bearer = _app_login(client, _ADMIN, installation="inst-vivo-1", name="vivo X200")
    # 没登记就连：409，App 据此先登记
    assert _as_app(client, bearer, "GET", _STREAM).status_code == 409
    view = _enable_stream(client, bearer)
    assert view == {
        "registered": True,
        "status": "ok",
        "status_text": "能收到",
        "channel_name": "实时连接",
    }
    # 服务器没连云、没有中继也算能收到通知
    mine = _data(client.get("/api/v1/push/me"))
    assert mine["instance_ready"] is True and mine["ready_devices"] == 1
    listed = _data(client.get(f"{_AUTH}/devices"))
    phone = next(d for d in listed if d["name"] == "vivo X200")
    assert phone["push"] == {"status": "ok", "status_text": "能收到"}

    # iOS 有 APNs，不能开实时通知；网页会话不能登记
    ios = _app_login(client, _ADMIN, installation="inst-iphone-1", name="iPhone", kind="ios")
    resp = _as_app(
        client,
        ios,
        "PUT",
        "/api/v1/push/me/registration",
        json={"permission": "authorized", "stream": True},
    )
    assert resp.status_code == 400
    assert _as_app(client, ios, "GET", _STREAM).status_code == 404
    resp = client.put(
        "/api/v1/push/me/registration", json={"permission": "authorized", "stream": True}
    )
    assert resp.status_code == 403
    # 带令牌又要实时通知：说不通
    resp = _as_app(
        client,
        bearer,
        "PUT",
        "/api/v1/push/me/registration",
        json={"permission": "authorized", "stream": True, "token": "ab" * 32},
    )
    assert resp.status_code == 400

    # 系统通知权限关了：显示出来、不再写发件箱
    resp = _as_app(
        client,
        bearer,
        "PUT",
        "/api/v1/push/me/registration",
        json={"permission": "denied", "stream": True},
    )
    assert _data(resp)["status"] == "permission_denied"
    _notify(client, {0}, "关了权限")
    time.sleep(0.3)
    assert all(r.payload["title"] != "关了权限" for r in _rows(client))

    # 关掉实时通知：不再算能收到
    _enable_stream(client, bearer, on=False)
    mine = _data(client.get("/api/v1/push/me"))
    assert mine["ready_devices"] == 0 and mine["instance_ready"] is False
    assert _as_app(client, bearer, "GET", _STREAM).status_code == 409


def test_unauthenticated_stream_rejected(client: TestClient) -> None:
    client.cookies.clear()
    assert client.get(_STREAM).status_code == 401
    assert client.post("/api/v1/push/me/ack", json={"up_to": 1}).status_code == 401


# ----------------------------------------------------------------------
# 写发件箱：只给实时通知设备，遵守偏好
# ----------------------------------------------------------------------


def test_outbox_only_for_stream_devices_and_respects_preferences(client: TestClient) -> None:
    stream_bearer = _app_login(client, _ADMIN, installation="inst-vivo-1", name="vivo")
    _app_login(client, _ADMIN, installation="inst-pixel-1", name="Pixel（没开实时通知）")
    _enable_stream(client, stream_bearer)
    _notify(client, {0}, "沙丘")
    _wait(lambda: len(_rows(client)) == 1)
    rows = _rows(client)
    device_ids = {r.login_device_id for r in rows}
    assert len(device_ids) == 1
    payload = rows[0].payload
    assert payload["title"] == "沙丘" and payload["body"] == "正文"
    assert payload["event"] == "imported" and payload["deeplink"] == "/library/1/item/2"
    assert payload["priority"] == "passive" and payload["thread"] == "t"
    assert len(payload["collapse"]) == 16  # 和中继同一个 collapse_id
    assert payload["account"]["id"] == "0" and payload["server"]["name"]

    # 关掉「入库完成」：不再写
    _data(client.put("/api/v1/push/me/preferences", json={"events": {"imported": False}}))
    _notify(client, {0}, "奥本海默")
    time.sleep(0.4)
    assert len(_rows(client)) == 1
    # 别人的事件不写到我的设备
    _data(client.put("/api/v1/push/me/preferences", json={"events": {"imported": True}}))
    _notify(client, {12345}, "不是我的")
    time.sleep(0.4)
    assert len(_rows(client)) == 1


# ----------------------------------------------------------------------
# 实时连接：补发 → 实时 → 心跳；确认后不再补发
# ----------------------------------------------------------------------


def test_stream_replays_then_streams_live_with_heartbeat(client: TestClient) -> None:
    bearer = _app_login(client, _ADMIN, installation="inst-vivo-1", name="vivo")
    _enable_stream(client, bearer)
    _notify(client, {0}, "第一条")
    _wait(lambda: len(_rows(client)) == 1)
    _notify(client, {0}, "第二条")
    _wait(lambda: len(_rows(client)) == 2)
    first, second = (r.id for r in _rows(client))

    # 连着的时候再来一条：应该实时送到
    timer = threading.Timer(0.6, lambda: _notify(client, {0}, "实时的"))
    timer.start()
    started = time.time()
    resp = _as_app(client, bearer, "GET", _STREAM, params={"since": 0})
    timer.join()
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/event-stream")
    assert time.time() - started >= 1.4  # 到连接时长上限才正常结束
    frames = _parse(resp.text)
    assert frames[0] == {"comment": "connected"}
    items = _notifications(frames)
    assert [i["title"] for i in items] == ["第一条", "第二条", "实时的"]
    assert [i["id"] for i in items[:2]] == [first, second]
    ids = [f["id"] for f in frames if f.get("event") == "notification"]
    assert ids == [str(i["id"]) for i in items]
    assert any(f.get("comment") == "heartbeat" for f in frames)

    # 游标：since 之后的才补发；Last-Event-ID 同义（取两者较大的）
    resp = _as_app(client, bearer, "GET", _STREAM, params={"since": first})
    assert [i["title"] for i in _notifications(_parse(resp.text))] == ["第二条", "实时的"]
    resp = _as_app(client, bearer, "GET", _STREAM, headers={"Last-Event-ID": str(items[-1]["id"])})
    assert _notifications(_parse(resp.text)) == []

    # 确认到第二条：之后从头连也只补发没确认的
    ack = _as_app(client, bearer, "POST", "/api/v1/push/me/ack", json={"up_to": second})
    assert _data(ack) == {"acked": 2}
    resp = _as_app(client, bearer, "GET", _STREAM)
    assert [i["title"] for i in _notifications(_parse(resp.text))] == ["实时的"]


def test_other_accounts_cannot_read_or_ack(client: TestClient) -> None:
    resp = client.post("/api/v1/members", json={**_MEMBER, "nickname": "家人"})
    assert resp.status_code in (200, 201), resp.text
    member_id = resp.json()["data"]["id"]
    mine = _app_login(client, _ADMIN, installation="inst-vivo-admin", name="管理员的 vivo")
    theirs = _app_login(client, _MEMBER, installation="inst-vivo-family", name="家人的 vivo")
    _enable_stream(client, mine)
    _enable_stream(client, theirs)
    _notify(client, {0}, "给管理员的")
    _wait(lambda: len(_rows(client)) == 1)
    admin_row = _rows(client)[0].id
    # 家人的连接看不到，确认管理员的 id 也动不了管理员的发件箱
    resp = _as_app(client, theirs, "GET", _STREAM)
    assert _notifications(_parse(resp.text)) == []
    assert (
        _data(_as_app(client, theirs, "POST", "/api/v1/push/me/ack", json={"up_to": admin_row}))[
            "acked"
        ]
        == 0
    )
    resp = _as_app(client, mine, "GET", _STREAM)
    assert [i["title"] for i in _notifications(_parse(resp.text))] == ["给管理员的"]
    # 家人自己的
    _notify(client, {member_id}, "给家人的")
    _wait(lambda: len(_rows(client)) == 2)
    resp = _as_app(client, theirs, "GET", _STREAM)
    assert [i["title"] for i in _notifications(_parse(resp.text))] == ["给家人的"]


def test_test_push_goes_through_outbox(client: TestClient) -> None:
    bearer = _app_login(client, _ADMIN, installation="inst-vivo-1", name="vivo")
    _enable_stream(client, bearer)
    result = _data(_as_app(client, bearer, "POST", "/api/v1/push/me/test"))
    # 手机没连着：已进发件箱，连上后补发
    assert result["sent"] == 1
    assert result["results"][0]["result"] == "queued"
    resp = _as_app(client, bearer, "GET", _STREAM)
    items = _notifications(_parse(resp.text))
    assert items[0]["title"] == "测试通知" and items[0]["event"] == "test"
    assert items[0]["deeplink"] == "/settings/notifications"


# ----------------------------------------------------------------------
# 清理
# ----------------------------------------------------------------------


def test_pruning_and_clear_on_disable(client: TestClient, monkeypatch) -> None:  # type: ignore[no-untyped-def]
    from movieclaw_api.services.push import outbox
    from movieclaw_db.engine import get_database
    from movieclaw_db.models import PushOutbox, utcnow

    monkeypatch.setattr(outbox, "MAX_PENDING", 3)
    bearer = _app_login(client, _ADMIN, installation="inst-vivo-1", name="vivo")
    _enable_stream(client, bearer)
    device_id = int(
        next(d["id"] for d in _data(client.get(f"{_AUTH}/devices")) if d["name"] == "vivo")[3:]
    )

    async def write(n: int, *, age: timedelta | None = None, delivered: bool = False) -> None:
        async with get_database().session() as session:
            for i in range(n):
                row_id = await outbox.enqueue(session, device_id, {"title": f"#{i}"})
                if age is not None or delivered:
                    row = await session.get(PushOutbox, row_id)
                    assert row is not None
                    if age is not None:
                        row.created_at = utcnow() - age
                    if delivered:
                        row.delivered_at = utcnow()
            await session.commit()

    # 未送达的最多留 3 条：丢最旧的
    _call(client, lambda: write(5))
    assert [r.payload["title"] for r in _rows(client)] == ["#2", "#3", "#4"]
    last = _rows(client)[-1].id
    # 过期的、已送达的在下一次写入时清掉；id 不回退
    _call(client, lambda: write(1, age=timedelta(days=8)))
    assert len(_rows(client)) == 3
    _call(client, lambda: write(1, delivered=True))
    _call(client, lambda: write(1))
    rows = _rows(client)
    assert all(r.delivered_at is None for r in rows)
    assert all(r.created_at > utcnow() - timedelta(days=1) for r in rows)
    assert rows[-1].id > last
    # 关掉实时通知：发件箱清空
    _enable_stream(client, bearer, on=False)
    assert _rows(client) == []
    # 注销设备：随行删除（外键级联）
    _enable_stream(client, bearer)
    _call(client, lambda: write(2))
    assert len(_rows(client)) == 2
    resp = client.delete(f"{_AUTH}/devices/ld-{device_id}")
    assert resp.status_code == 200, resp.text
    assert _rows(client) == []
