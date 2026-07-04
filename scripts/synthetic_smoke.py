#!/usr/bin/env python3
"""
Post-deploy smoke test for Cookbook FastAPI server.
"""

import urllib.request
import urllib.error
import json
import os
import sys
import uuid
import time

BASE = os.environ.get("COOKBOOK_URL", "http://127.0.0.1:8003")
# NB: pydantic EmailStr rejects reserved TLDs like .local — use a real domain
EMAIL = os.environ.get("SMOKE_EMAIL", "smoke-test@dragonflymedia.org")
PASSWORD = os.environ.get("SMOKE_PASSWORD", "Smoke-Test-2026!")
# Runs inside the server container (deploy.yml), where REGISTRATION_INVITE_CODE is already set.
INVITE = os.environ.get("SMOKE_INVITE_CODE") or os.environ.get("REGISTRATION_INVITE_CODE")

def request(method, path, body=None, token=None):
    url = BASE + path
    data = None
    if body is not None:
        data = json.dumps(body).encode('utf-8')

    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', f'Bearer {token}')

    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            content = response.read()
            try:
                return response.getcode(), json.loads(content)
            except json.JSONDecodeError:
                return response.getcode(), content.decode('utf-8')
    except urllib.error.HTTPError as e:
        content = e.read()
        try:
            parsed = json.loads(content)
            return e.code, parsed
        except json.JSONDecodeError:
            return e.code, content.decode('utf-8')

def main():
    # Step a: Register or login
    access_token = None

    register_body = {
        "name": "Smoke Test User",
        "email": EMAIL,
        "password": PASSWORD
    }

    if INVITE:
        register_body["invite_code"] = INVITE

    status, result = request("POST", "/auth/register", register_body)

    if status == 201:
        access_token = result.get("access_token")
    elif status in (400, 409):
        # User already exists (Cookbook returns 400, per Plate convention), try login
        status, result = request("POST", "/auth/login", {
            "email": EMAIL,
            "password": PASSWORD
        })
        if status != 200:
            print(f"[FAIL] login: HTTP {status} {str(result)[:200]}")
            sys.exit(1)
        access_token = result.get("access_token")
    elif status == 429:
        # Rate limited, wait and retry once
        time.sleep(15)
        status, result = request("POST", "/auth/register", register_body)
        if status == 201:
            access_token = result.get("access_token")
        elif status in (400, 409):
            # User already exists (Cookbook returns 400, per Plate convention), try login
            status, result = request("POST", "/auth/login", {
                "email": EMAIL,
                "password": PASSWORD
            })
            if status != 200:
                print(f"[FAIL] retry login: HTTP {status} {str(result)[:200]}")
                sys.exit(1)
            access_token = result.get("access_token")
        else:
            print(f"[FAIL] register retry: HTTP {status} {str(result)[:200]}")
            sys.exit(1)
    else:
        print(f"[FAIL] register: HTTP {status} {str(result)[:200]}")
        sys.exit(1)

    if not access_token:
        print("[FAIL] No access token obtained")
        sys.exit(1)

    # Step b: GET /recipes
    status, result = request("GET", "/recipes", token=access_token)
    if status != 200:
        print(f"[FAIL] read recipes: HTTP {status} {str(result)[:200]}")
        sys.exit(1)

    if not isinstance(result, list):
        print(f"[FAIL] read recipes: Expected list, got {type(result)}")
        sys.exit(1)

    print(f"[ok] read {len(result)} recipes")

    # Step c: POST /recipes
    recipe_name = "smoke-" + uuid.uuid4().hex[:8]
    status, result = request("POST", "/recipes", {"name": recipe_name}, token=access_token)
    if status != 201:
        print(f"[FAIL] create recipe: HTTP {status} {str(result)[:200]}")
        sys.exit(1)

    recipe_id = result.get("id") if isinstance(result, dict) else None
    print(f"[ok] created {recipe_name}")

    # Step d: GET /recipes again to verify write
    status, result = request("GET", "/recipes", token=access_token)
    if status != 200:
        print(f"[FAIL] read recipes after create: HTTP {status} {str(result)[:200]}")
        sys.exit(1)

    if not isinstance(result, list):
        print(f"[FAIL] read recipes after create: Expected list, got {type(result)}")
        sys.exit(1)

    names = [r.get("name") for r in result if isinstance(r, dict) and "name" in r]
    if recipe_name not in names:
        print(f"[FAIL] write verification: Recipe '{recipe_name}' not found in list")
        sys.exit(1)

    print("[ok] write verified")

    # Cleanup: delete the smoke recipe so prod doesn't accumulate one per deploy (best-effort).
    if recipe_id:
        status, _ = request("DELETE", f"/recipes/{recipe_id}", token=access_token)
        print(f"[ok] cleaned up (HTTP {status})" if status in (200, 204) else f"[warn] cleanup HTTP {status}")

    # All steps passed
    print("SMOKE_PASS")
    sys.exit(0)

if __name__ == "__main__":
    main()