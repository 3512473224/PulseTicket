import concurrent.futures
import json
import sys
import time
import urllib.error
import urllib.request

GATEWAY_URL = "http://127.0.0.1:8200"


def http_request(method: str, path: str, body: dict = None, token: str = None):
    url = f"{GATEWAY_URL}{path}"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            parsed = json.loads(raw) if raw else {}
        except Exception:
            parsed = {"raw": raw}
        return e.code, parsed


def assert_true(cond: bool, msg: str):
    if not cond:
        print(f"❌ ASSERTION FAILED: {msg}")
        sys.exit(1)
    print(f"  ✅ {msg}")


def main():
    print("=" * 74)
    print("🚀 PulseTicket Spring Cloud Alibaba E2E Microservice Verification Suite")
    print("=" * 74)

    # 1. Login & Gateway ThreadLocal Relay + /internal/** Security Block
    print("\n[Test 1] Gateway JWT Auth + ThreadLocal Context Relay + /internal/** Block")
    status, login_res = http_request("POST", "/api/user/auth/login", {"username": "zhaolan", "password": "Ticket@2026"})
    assert_true(status == 200 and login_res.get("code") == 200, f"Login succeeded for zhaolan (HTTP {status})")
    token_ppday = login_res["data"]["accessToken"]

    _, login_lin = http_request("POST", "/api/user/auth/login", {"username": "linfeng", "password": "Ticket@2026"})
    token_lin = login_lin["data"]["accessToken"]
    _, login_su = http_request("POST", "/api/user/auth/login", {"username": "suwan", "password": "Ticket@2026"})
    token_su = login_su["data"]["accessToken"]

    status, profile_res = http_request("GET", "/api/user/profile", token=token_ppday)
    assert_true(
        status == 200
        and profile_res["data"]["threadLocalUsername"] == "zhaolan"
        and profile_res["data"]["threadLocalUserId"] == 1001,
        f"ThreadLocal UserContext resolved userId={profile_res['data']['threadLocalUserId']}, "
        f"username={profile_res['data']['threadLocalUsername']}, traceId={profile_res['data']['traceId']}"
    )

    status_internal, _ = http_request(
        "POST",
        "/internal/show/seats/lock",
        {"orderNo": "HACK01", "userId": 1, "sessionId": 101, "seatIds": [1]},
        token=token_ppday,
    )
    assert_true(
        status_internal in (403, 404),
        f"Gateway blocked external /internal/** call with HTTP {status_internal} (never routed to downstream)"
    )

    # 2. Redis 3-Mountain Cache Protection (Penetration / Breakdown / Avalanche)
    print("\n[Test 2] Redis 3-Mountain Cache Protection (Mutex Rebuild + Hit + Null Sentinel)")
    http_request("POST", "/api/show/cache/evict/1", token=token_ppday)
    _, show_miss = http_request("GET", "/api/show/public/detail/1")
    assert_true(
        show_miss["data"]["cacheStatus"] == "MYSQL_MUTEX_REBUILT",
        f"After eviction, 1st query triggered Redisson Mutex Rebuild: cacheStatus={show_miss['data']['cacheStatus']}, "
        f"ttlSeconds={show_miss['data']['ttlSeconds']} (1800s + random jitter)"
    )
    _, show_hit = http_request("GET", "/api/show/public/detail/1")
    assert_true(
        show_hit["data"]["cacheStatus"] == "REDIS_HIT",
        f"2nd query hit Redis cache: cacheStatus={show_hit['data']['cacheStatus']}"
    )
    http_request("POST", "/api/show/cache/evict/99999", token=token_ppday)
    _, show_null_1 = http_request("GET", "/api/show/public/detail/99999")
    assert_true(
        show_null_1["data"]["cacheStatus"] == "PENETRATION_PROTECTED",
        f"Non-existent showId=99999 wrote __NULL__ sentinel: cacheStatus={show_null_1['data']['cacheStatus']}"
    )
    _, show_null_2 = http_request("GET", "/api/show/public/detail/99999")
    assert_true(
        show_null_2["data"]["cacheStatus"] == "REDIS_NULL_GUARD",
        f"Subsequent query for showId=99999 intercepted by Redis __NULL__: cacheStatus={show_null_2['data']['cacheStatus']}"
    )

    # Fetch available seats in session 101
    _, seat_map = http_request("GET", "/api/show/public/sessions/101/seats")
    available_seats = [s for s in seat_map["data"]["seats"] if s["status"] == "AVAILABLE"]
    assert_true(len(available_seats) >= 6, f"Session 101 has {len(available_seats)} AVAILABLE seats")

    # 3. High-Concurrency Seat-Grabbing Stress Test (30 concurrent requests for the exact same seat)
    print("\n[Test 3] 30-Thread Concurrency Stress Test on Same Seat (Redisson MultiLock + MySQL CAS)")
    target_seat_concurrent = available_seats[-1]
    seat_id_concurrent = target_seat_concurrent["id"]
    tokens = [token_ppday, token_lin, token_su]

    def grab_seat(idx: int):
        tk = tokens[idx % len(tokens)]
        return http_request(
            "POST",
            "/api/order/create",
            {
                "sessionId": 101,
                "seatIds": [seat_id_concurrent],
                "fastCloseDemo": False,
                "simulateSeataRollback": False,
            },
            token=tk,
        )

    with concurrent.futures.ThreadPoolExecutor(max_workers=30) as pool:
        results = list(pool.map(grab_seat, range(30)))

    success_res = [r for _, r in results if r.get("code") == 200]
    failed_res = [r for _, r in results if r.get("code") != 200]
    assert_true(
        len(success_res) == 1 and len(failed_res) == 29,
        f"30 concurrent threads grabbing seat {target_seat_concurrent['seatLabel']} (id={seat_id_concurrent}): "
        f"EXACTLY 1 succeeded, 29 rejected (0 oversell!)"
    )
    winning_order_no = success_res[0]["data"]["orderNo"]
    print(f"     Winning orderNo={winning_order_no}, sample reject msg='{failed_res[0].get('message')}'")

    # 4. Seata AT Distributed Transaction Fault Injection & Automatic Rollback
    print("\n[Test 4] Seata AT Distributed Transaction Fault Injection & Automatic Rollback")
    target_seat_seata = available_seats[-2]
    seat_id_seata = target_seat_seata["id"]
    _, seata_res = http_request(
        "POST",
        "/api/order/create",
        {
            "sessionId": 101,
            "seatIds": [seat_id_seata],
            "fastCloseDemo": False,
            "simulateSeataRollback": True,
        },
        token=token_ppday,
    )
    assert_true(
        seata_res.get("code") != 200 and "Seata AT" in seata_res.get("message", ""),
        f"Fault injection triggered exception as expected: {seata_res.get('message')}"
    )

    _, seat_map_after_seata = http_request("GET", "/api/show/public/sessions/101/seats")
    seat_after_rollback = next(s for s in seat_map_after_seata["data"]["seats"] if s["id"] == seat_id_seata)
    assert_true(
        seat_after_rollback["status"] == "AVAILABLE" and seat_after_rollback.get("lockedOrderNo") is None,
        f"Seata AT undo_log rolled back seat {seat_after_rollback['seatLabel']} (id={seat_id_seata}) to AVAILABLE!"
    )

    _, telemetry_res = http_request("GET", "/api/order/telemetry", token=token_ppday)
    rollbacks = [a for a in telemetry_res["data"]["seataAudits"] if a["txStatus"] == "ROLLED_BACK"]
    assert_true(
        len(rollbacks) >= 1,
        f"seata_tx_audit recorded ROLLED_BACK transaction with XID={rollbacks[0]['xid']}"
    )

    # 5. Order Payment + RabbitMQ Fanout -> Seat SOLD + Idempotent VIP Points & E-Ticket Message
    print("\n[Test 5] Order Payment + RabbitMQ Fanout -> Seat SOLD + Idempotent Points & E-Ticket")
    _, profile_before = http_request("GET", "/api/user/profile", token=token_ppday)
    points_before = profile_before["data"]["user"]["pointsBalance"]

    target_seat_pay = available_seats[-3]
    _, create_pay_res = http_request(
        "POST",
        "/api/order/create",
        {
            "sessionId": 101,
            "seatIds": [target_seat_pay["id"]],
            "fastCloseDemo": False,
            "simulateSeataRollback": False,
        },
        token=token_ppday,
    )
    pay_order_no = create_pay_res["data"]["orderNo"]
    _, pay_res = http_request("POST", f"/api/order/{pay_order_no}/pay", token=token_ppday)
    assert_true(
        pay_res.get("code") == 200 and pay_res["data"]["status"] == "PAID",
        f"Order {pay_order_no} paid successfully, ticketCode={pay_res['data']['ticketCode']}"
    )

    time.sleep(1.5)
    _, seat_map_after_pay = http_request("GET", "/api/show/public/sessions/101/seats")
    seat_sold = next(s for s in seat_map_after_pay["data"]["seats"] if s["id"] == target_seat_pay["id"])
    assert_true(
        seat_sold["status"] == "SOLD",
        f"RabbitMQ Fanout updated seat {seat_sold['seatLabel']} to SOLD in show-service"
    )

    _, profile_after = http_request("GET", "/api/user/profile", token=token_ppday)
    points_after = profile_after["data"]["user"]["pointsBalance"]
    assert_true(
        points_after > points_before,
        f"RabbitMQ Fanout rewarded VIP points in user-service: {points_before} -> {points_after}"
    )

    # 6. RabbitMQ 15-Second Fast DLX Delayed Auto-Close (Unpaid Timeout -> CLOSED + Seat Released)
    print("\n[Test 6] RabbitMQ 15s Fast DLX Delayed Auto-Close (TTL -> DLX -> Close Consumer)")
    target_seat_dlx = available_seats[-4]
    _, create_dlx_res = http_request(
        "POST",
        "/api/order/create",
        {
            "sessionId": 101,
            "seatIds": [target_seat_dlx["id"]],
            "fastCloseDemo": True,
            "simulateSeataRollback": False,
        },
        token=token_ppday,
    )
    dlx_order_no = create_dlx_res["data"]["orderNo"]
    assert_true(
        create_dlx_res.get("code") == 200 and create_dlx_res["data"]["status"] == "UNPAID",
        f"Created 15s Fast-DLX order {dlx_order_no} on seat {target_seat_dlx['seatLabel']} (status=LOCKED)"
    )
    print("     ⏳ Waiting 16.5 seconds for RabbitMQ 15s TTL expiration -> DLX -> Auto-Close...")
    time.sleep(16.5)

    _, dlx_order_after = http_request("GET", f"/api/order/detail/{dlx_order_no}", token=token_ppday)
    assert_true(
        dlx_order_after["data"]["status"] == "CLOSED"
        and dlx_order_after["data"]["closeReason"] == "TIMEOUT_UNPAID",
        f"Order {dlx_order_no} automatically transitioned to CLOSED (reason={dlx_order_after['data']['closeReason']})"
    )

    _, seat_map_after_dlx = http_request("GET", "/api/show/public/sessions/101/seats")
    seat_released = next(s for s in seat_map_after_dlx["data"]["seats"] if s["id"] == target_seat_dlx["id"])
    assert_true(
        seat_released["status"] == "AVAILABLE",
        f"Seat {seat_released['seatLabel']} automatically released back to AVAILABLE after DLX close!"
    )

    # Clean up winning_order_no from Test 3 so session 101 stays tidy
    http_request("POST", f"/api/order/{winning_order_no}/cancel", token=token_ppday)
    http_request("POST", f"/api/order/{winning_order_no}/cancel", token=token_lin)
    http_request("POST", f"/api/order/{winning_order_no}/cancel", token=token_su)

    print("\n" + "=" * 74)
    print("🎉 ALL 6 MICROSERVICE E2E VERIFICATION SUITES PASSED 100%!")
    print("=" * 74)


if __name__ == "__main__":
    main()
