#!/usr/bin/env python3
"""
Test that the API can write to non-writable memory blocks.

This is important for a loader-like API that may need to write to
read-only segments during the loading process.
"""

import json
import sys
import urllib.request
import urllib.error

# Configuration
HOST = "localhost"
PORT = 8193
BASE_URL = f"http://{HOST}:{PORT}"

def test_write_to_readonly_block():
    """Test writing to a read-only memory block."""
    print("=" * 70)
    print("Test: Write to Read-Only Memory Block")
    print("=" * 70)

    # Create a read-only memory block
    test_addr = "0x80000000"
    test_size = 0x1000
    test_name = "test_readonly_block_api_v2"

    print(f"\n1. Using existing read-only block at {test_addr}...")
    print(f"   Block name: {test_name}")

    # Verify the block is read-only
    print(f"\n2. Verifying block is read-only...")
    try:
        with urllib.request.urlopen(f"{BASE_URL}/memory/blocks") as resp:
            data = json.loads(resp.read().decode())
            blocks = data.get("result", [])
            test_block = None
            for block in blocks:
                if block.get("name") == test_name:
                    test_block = block
                    break

            if test_block is None:
                print(f"   [ERROR] Block not found")
                return False

            permissions = test_block.get("permissions", "")
            print(f"   Block permissions: {permissions}")
            if "w" in permissions:
                print(f"   [ERROR] Block should be read-only but has write permission")
                return False
            print(f"   [OK] Block is read-only as expected")
    except Exception as e:
        print(f"   [ERROR] Failed to verify block: {e}")
        return False

    # Try to write to the read-only block
    print(f"\n3. Writing to read-only block...")
    test_bytes = bytes([0xDE, 0xAD, 0xBE, 0xEF])
    write_payload = {
        "bytes": test_bytes.hex(),
        "format": "hex"
    }

    try:
        req = urllib.request.Request(
            f"{BASE_URL}/memory/{test_addr}",
            data=json.dumps(write_payload).encode(),
            headers={"Content-Type": "application/json"},
            method="PATCH"
        )
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            print(f"   Write succeeded: HTTP {resp.status}")
            print(f"   Bytes written: {data.get('bytesWritten', 0)}")
            print(f"   autoMadeWritable: {data.get('autoMadeWritable', False)}")

            if not data.get('autoMadeWritable', False):
                print(f"   [WARNING] autoMadeWritable flag not set, but write succeeded")

            print(f"   [OK] Write to read-only block succeeded")
    except urllib.error.HTTPError as e:
        print(f"   [ERROR] Write failed: HTTP {e.code}")
        print(f"   Response: {e.read().decode()}")
        return False

    # Verify the data was written
    print(f"\n4. Verifying data was written...")
    try:
        req = urllib.request.Request(
            f"{BASE_URL}/memory/read",
            data=json.dumps({
                "address": test_addr,
                "length": str(len(test_bytes)),
                "format": "hex"
            }).encode(),
            headers={"Content-Type": "application/json"},
            method="POST"
        )
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            read_hex = data.get("hexBytes", "")
            print(f"   Read bytes: {read_hex}")

            if read_hex.lower() != test_bytes.hex().lower():
                print(f"   [ERROR] Data mismatch: expected {test_bytes.hex()}, got {read_hex}")
                return False

            print(f"   [OK] Data matches")
    except Exception as e:
        print(f"   [ERROR] Failed to verify data: {e}")
        return False

    # Verify the block is still read-only after the write
    print(f"\n5. Verifying block is still read-only after write...")
    try:
        with urllib.request.urlopen(f"{BASE_URL}/memory/blocks") as resp:
            data = json.loads(resp.read().decode())
            blocks = data.get("result", [])
            test_block = None
            for block in blocks:
                if block.get("name") == test_name:
                    test_block = block
                    break

            if test_block is None:
                print(f"   [ERROR] Block not found")
                return False

            permissions = test_block.get("permissions", "")
            print(f"   Block permissions: {permissions}")
            if "w" in permissions:
                print(f"   [ERROR] Block should still be read-only but has write permission")
                return False
            print(f"   [OK] Block is still read-only as expected")
    except Exception as e:
        print(f"   [ERROR] Failed to verify block: {e}")
        return False

    # Cleanup
    print(f"\n6. Cleaning up test block...")
    try:
        req = urllib.request.Request(
            f"{BASE_URL}/memory/blocks/{test_name}",
            method="DELETE"
        )
        with urllib.request.urlopen(req) as resp:
            print(f"   Block deleted: HTTP {resp.status}")
    except urllib.error.HTTPError as e:
        print(f"   [WARNING] Failed to delete block: HTTP {e.code}")

    print("\n" + "=" * 70)
    print("[SUCCESS] All tests passed!")
    print("=" * 70)
    return True

if __name__ == "__main__":
    try:
        success = test_write_to_readonly_block()
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"\n[FATAL] Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)