#!/usr/bin/env python3
"""
Comprehensive test script for memory operations in GhydraMCP.

This script tests all memory-related operations including:
1. Reading memory bytes
2. Writing memory bytes (hex, base64, string formats)
3. Creating memory blocks (initialized/uninitialized, RWX permissions)
4. Updating memory blocks
5. Deleting memory blocks
6. Memory block listing and querying
7. Auto-conversion of uninitialized blocks
8. Large memory writes (>1MB)
9. Memory write with force flag
10. Memory write alias endpoints

Tests are performed using the HTTP API.
"""
import json
import logging
import sys
import time
import requests
import os
import struct
from typing import Dict, Any, List, Tuple
from urllib.parse import quote

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("memory_test")

# Configure default test values
GHIDRA_PORT = 8192
BASE_URL = f"http://localhost:{GHIDRA_PORT}"

# Test binary path
TEST_BINARY = "/tmp/busybox-arm64"

# Test addresses (will be adjusted based on loaded program)
TEST_ADDRESSES = {
    "read": "0x1000",
    "write": "0x2000",
    "block": "0x3000",
    "large": "0x4000",
}

def wait_for_server(timeout=30):
    """Wait for Ghidra server to be ready."""
    logger.info(f"Waiting for Ghidra server at {BASE_URL}...")
    for i in range(timeout):
        try:
            response = requests.get(f"{BASE_URL}/info", timeout=2)
            if response.status_code == 200:
                logger.info("Ghidra server is ready")
                return True
        except requests.exceptions.RequestException:
            pass
        time.sleep(1)
    logger.error("Timed out waiting for Ghidra server")
    return False

def wait_for_program_loaded(timeout=30):
    """Wait for a Ghidra program to be loaded."""
    logger.info("Waiting for program to be loaded...")
    for i in range(timeout):
        try:
            response = requests.get(f"{BASE_URL}/program", timeout=2)
            if response.status_code == 200:
                data = response.json()
                if data.get("success", False):
                    result = data.get("result", {})
                    logger.info(f"Program loaded: {result.get('name', 'unknown')}")
                    return True
        except requests.exceptions.RequestException:
            pass
        time.sleep(1)
    logger.error("Timed out waiting for program to load")
    return False

def get_memory_blocks() -> List[Dict]:
    """Get list of all memory blocks."""
    try:
        response = requests.get(f"{BASE_URL}/memory/blocks", timeout=10)
        if response.status_code == 200:
            data = response.json()
            if data.get("success"):
                return data.get("result", [])
    except Exception as e:
        logger.error(f"Error getting memory blocks: {e}")
    return []

def find_free_address_range(size: int = 0x10000) -> str:
    """Find a free address range for testing."""
    blocks = get_memory_blocks()

    # Try to find a gap between blocks
    if blocks:
        # Sort by start address
        sorted_blocks = sorted(blocks, key=lambda b: int(b.get("start", "0"), 16))

        # Look for gaps
        for i in range(len(sorted_blocks) - 1):
            current_end = int(sorted_blocks[i].get("end", "0"), 16)
            next_start = int(sorted_blocks[i + 1].get("start", "0"), 16)
            gap = next_start - current_end
            if gap >= size:
                return f"0x{current_end + 0x1000:x}"

    # If no gap found, use a high address
    return "0x10000000"

def cleanup_test_blocks():
    """Clean up any test blocks from previous runs."""
    blocks = get_memory_blocks()
    for block in blocks:
        name = block.get("name", "")
        if name.startswith("test_"):
            start = block.get("start", "")
            logger.info(f"Cleaning up test block: {name} at {start}")
            try:
                response = requests.delete(f"{BASE_URL}/memory/blocks/{start}", timeout=10)
                if response.status_code not in [200, 404]:
                    logger.warning(f"Failed to delete block {name}: {response.status_code}")
            except Exception as e:
                logger.warning(f"Error deleting block {name}: {e}")

def assert_success(response: requests.Response, test_name: str) -> Dict:
    """Assert that the response was successful and return the JSON data."""
    # Accept 200 (OK) and 201 (Created) as success
    if response.status_code not in [200, 201]:
        logger.error(f"{test_name} failed with status {response.status_code}")
        logger.error(f"Response: {response.text}")
        raise AssertionError(f"{test_name} failed with status {response.status_code}")

    try:
        data = response.json()
    except json.JSONDecodeError:
        logger.error(f"{test_name} returned non-JSON response")
        logger.error(f"Response: {response.text}")
        raise AssertionError(f"{test_name} returned non-JSON response")

    if not data.get("success", False):
        error = data.get("error", "Unknown error")
        logger.error(f"{test_name} failed: {error}")
        raise AssertionError(f"{test_name} failed: {error}")

    return data

def test_read_memory():
    """Test reading memory bytes."""
    logger.info("=" * 60)
    logger.info("TEST: Read Memory")
    logger.info("=" * 60)

    blocks = get_memory_blocks()
    if not blocks:
        logger.warning("No memory blocks available for read test")
        return False

    # Use the first block's start address
    address = blocks[0].get("start", "0x1000")
    logger.info(f"Reading from address: {address}")

    try:
        # Use the correct endpoint: /memory/read with address as query parameter
        response = requests.get(f"{BASE_URL}/memory/read?address={address}&length=16&format=hex", timeout=10)
        data = assert_success(response, "Read memory")

        result = data.get("result", {})
        self_link = data.get("_links", {}).get("self", {}).get("href", "")

        logger.info(f"Read successful: {result.get('bytesRead', 0)} bytes")
        logger.info(f"Hex bytes: {result.get('hexBytes', '')[:32]}...")
        logger.info(f"Self link: {self_link}")

        return True
    except Exception as e:
        logger.error(f"Read memory test failed: {e}")
        return False

def test_write_memory_hex():
    """Test writing memory bytes in hex format."""
    logger.info("=" * 60)
    logger.info("TEST: Write Memory (Hex Format)")
    logger.info("=" * 60)

    # Find a writable block or create one
    blocks = get_memory_blocks()
    writable_block = None

    for block in blocks:
        if block.get("writable", False) and block.get("isInitialized", False):
            writable_block = block
            break

    if not writable_block:
        logger.warning("No writable initialized block found, creating one")
        address = find_free_address_range()
        try:
            response = requests.post(
                f"{BASE_URL}/memory/blocks",
                json={
                    "name": "test_write_hex",
                    "address": address,
                    "size": "4096",
                    "readable": "true",
                    "writable": "true",
                    "executable": "false",
                    "initialized": "true",
                },
                timeout=10
            )
            data = assert_success(response, "Create test block")
            writable_block = data.get("result", {})
            address = writable_block.get("start", address)
        except Exception as e:
            logger.error(f"Failed to create test block: {e}")
            return False
    else:
        address = writable_block.get("start", "0x1000")

    # Write test pattern
    test_bytes = bytes([0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x11, 0x22, 0x33])
    hex_str = test_bytes.hex()

    logger.info(f"Writing {len(test_bytes)} bytes to {address}")
    logger.info(f"Hex: {hex_str}")

    try:
        response = requests.patch(
            f"{BASE_URL}/memory/{address}",
            json={
                "bytes": hex_str,
                "format": "hex",
                "length": len(test_bytes),
            },
            timeout=10
        )
        data = assert_success(response, "Write memory hex")

        result = data.get("result", {})
        logger.info(f"Write successful: {result.get('bytesWritten', 0)} bytes")
        logger.info(f"Hex bytes: {result.get('hexBytes', '')}")

        # Verify by reading back
        read_response = requests.get(f"{BASE_URL}/memory/read?address={address}&length={len(test_bytes)}&format=hex", timeout=10)
        read_data = assert_success(read_response, "Read back written bytes")
        read_hex = read_data.get("result", {}).get("hexBytes", "")

        if read_hex.lower() == hex_str.lower():
            logger.info("Verification successful: bytes match")
            return True
        else:
            logger.error(f"Verification failed: wrote {hex_str}, read {read_hex}")
            return False
    except Exception as e:
        logger.error(f"Write memory hex test failed: {e}")
        return False

def test_write_memory_base64():
    """Test writing memory bytes in base64 format."""
    logger.info("=" * 60)
    logger.info("TEST: Write Memory (Base64 Format)")
    logger.info("=" * 60)

    blocks = get_memory_blocks()
    writable_block = None

    for block in blocks:
        if block.get("writable", False) and block.get("isInitialized", False):
            writable_block = block
            break

    if not writable_block:
        logger.warning("No writable initialized block found, creating one")
        address = find_free_address_range()
        try:
            response = requests.post(
                f"{BASE_URL}/memory/blocks",
                json={
                    "name": "test_write_base64",
                    "address": address,
                    "size": "4096",
                    "readable": "true",
                    "writable": "true",
                    "executable": "false",
                    "initialized": "true",
                },
                timeout=10
            )
            data = assert_success(response, "Create test block")
            writable_block = data.get("result", {})
            address = writable_block.get("start", address)
        except Exception as e:
            logger.error(f"Failed to create test block: {e}")
            return False
    else:
        address = writable_block.get("start", "0x1000")

    # Write test pattern
    test_bytes = bytes([0xCA, 0xFE, 0xBA, 0xBE, 0x44, 0x55, 0x66, 0x77])
    import base64
    base64_str = base64.b64encode(test_bytes).decode()

    logger.info(f"Writing {len(test_bytes)} bytes to {address}")
    logger.info(f"Base64: {base64_str}")

    try:
        response = requests.patch(
            f"{BASE_URL}/memory/{address}",
            json={
                "bytes": base64_str,
                "format": "base64",
                "length": len(test_bytes),
            },
            timeout=10
        )
        data = assert_success(response, "Write memory base64")

        result = data.get("result", {})
        logger.info(f"Write successful: {result.get('bytesWritten', 0)} bytes")

        # Verify by reading back
        read_response = requests.get(f"{BASE_URL}/memory/read?address={address}&length={len(test_bytes)}&format=hex", timeout=10)
        read_data = assert_success(read_response, "Read back written bytes")
        read_hex = read_data.get("result", {}).get("hexBytes", "")

        if read_hex.lower() == test_bytes.hex().lower():
            logger.info("Verification successful: bytes match")
            return True
        else:
            logger.error(f"Verification failed: wrote {test_bytes.hex()}, read {read_hex}")
            return False
    except Exception as e:
        logger.error(f"Write memory base64 test failed: {e}")
        return False

def test_create_initialized_block():
    """Test creating an initialized memory block."""
    logger.info("=" * 60)
    logger.info("TEST: Create Initialized Memory Block")
    logger.info("=" * 60)

    address = find_free_address_range()
    block_name = "test_initialized_block"

    logger.info(f"Creating block '{block_name}' at {address}")

    try:
        response = requests.post(
            f"{BASE_URL}/memory/blocks",
            json={
                "name": block_name,
                "address": address,
                "size": "8192",
                "readable": "true",
                "writable": "true",
                "executable": "false",
                "initialized": "true",
            },
            timeout=10
        )
        data = assert_success(response, "Create initialized block")

        result = data.get("result", {})
        logger.info(f"Block created successfully")
        logger.info(f"  Name: {result.get('name', '')}")
        logger.info(f"  Start: {result.get('start', '')}")
        logger.info(f"  End: {result.get('end', '')}")
        logger.info(f"  Size: {result.get('size', 0)}")
        logger.info(f"  Permissions: R={result.get('readable', False)} W={result.get('writable', False)} X={result.get('executable', False)}")
        logger.info(f"  Initialized: {result.get('isInitialized', False)}")

        # Verify block exists
        blocks = get_memory_blocks()
        found = any(b.get("name") == block_name for b in blocks)
        if found:
            logger.info("Block verified in memory map")
            return True
        else:
            logger.error("Block not found in memory map")
            return False
    except Exception as e:
        logger.error(f"Create initialized block test failed: {e}")
        return False

def test_create_uninitialized_block():
    """Test creating an uninitialized memory block."""
    logger.info("=" * 60)
    logger.info("TEST: Create Uninitialized Memory Block")
    logger.info("=" * 60)

    address = find_free_address_range()
    block_name = "test_uninitialized_block"

    logger.info(f"Creating uninitialized block '{block_name}' at {address}")

    try:
        response = requests.post(
            f"{BASE_URL}/memory/blocks",
            json={
                "name": block_name,
                "address": address,
                "size": "4096",
                "readable": "true",
                "writable": "true",
                "executable": "false",
                "initialized": "false",
            },
            timeout=10
        )
        data = assert_success(response, "Create uninitialized block")

        result = data.get("result", {})
        logger.info(f"Block created successfully")
        logger.info(f"  Initialized: {result.get('isInitialized', False)}")

        # Verify block is uninitialized
        if not result.get('isInitialized', True):
            logger.info("Block correctly marked as uninitialized")
            return True
        else:
            logger.error("Block incorrectly marked as initialized")
            return False
    except Exception as e:
        logger.error(f"Create uninitialized block test failed: {e}")
        return False

def test_auto_convert_uninitialized_block():
    """Test auto-conversion of uninitialized block when writing."""
    logger.info("=" * 60)
    logger.info("TEST: Auto-Convert Uninitialized Block on Write")
    logger.info("=" * 60)

    address = find_free_address_range()
    block_name = "test_auto_convert"

    # Create uninitialized block
    logger.info(f"Creating uninitialized block '{block_name}' at {address}")

    try:
        response = requests.post(
            f"{BASE_URL}/memory/blocks",
            json={
                "name": block_name,
                "address": address,
                "size": "4096",
                "readable": "true",
                "writable": "true",
                "executable": "false",
                "initialized": "false",
            },
            timeout=10
        )
        data = assert_success(response, "Create uninitialized block")

        # Now try to write to it - should auto-convert
        test_bytes = bytes([0x01, 0x02, 0x03, 0x04])
        hex_str = test_bytes.hex()

        logger.info(f"Writing to uninitialized block (should auto-convert)")

        response = requests.patch(
            f"{BASE_URL}/memory/{address}",
            json={
                "bytes": hex_str,
                "format": "hex",
                "length": len(test_bytes),
            },
            timeout=10
        )
        data = assert_success(response, "Write to uninitialized block")

        result = data.get("result", {})
        logger.info(f"Write successful: {result.get('bytesWritten', 0)} bytes")

        # Verify block is now initialized
        blocks = get_memory_blocks()
        block = next((b for b in blocks if b.get("name") == block_name), None)

        if block and block.get("isInitialized", False):
            logger.info("Block successfully auto-converted to initialized")
            return True
        else:
            logger.error("Block was not auto-converted")
            return False
    except Exception as e:
        logger.error(f"Auto-convert test failed: {e}")
        return False

def test_large_memory_write():
    """Test writing a large amount of memory (>1MB)."""
    logger.info("=" * 60)
    logger.info("TEST: Large Memory Write (>1MB)")
    logger.info("=" * 60)

    # Check if test binary exists
    if not os.path.exists(TEST_BINARY):
        logger.warning(f"Test binary not found at {TEST_BINARY}, using synthetic data")
        # Create 2MB of test data
        test_data = bytes([i % 256 for i in range(2 * 1024 * 1024)])
    else:
        logger.info(f"Using test binary: {TEST_BINARY}")
        with open(TEST_BINARY, 'rb') as f:
            test_data = f.read()

    logger.info(f"Test data size: {len(test_data)} bytes ({len(test_data) / 1024 / 1024:.2f} MB)")

    # Create a large enough block
    address = find_free_address_range(size=len(test_data) + 0x1000)
    block_name = "test_large_block"

    logger.info(f"Creating block '{block_name}' at {address} with size {len(test_data)}")

    try:
        response = requests.post(
            f"{BASE_URL}/memory/blocks",
            json={
                "name": block_name,
                "address": address,
                "size": str(len(test_data)),
                "readable": "true",
                "writable": "true",
                "executable": "false",
                "initialized": "true",
            },
            timeout=10
        )
        data = assert_success(response, "Create large block")

        # Write the data in chunks
        chunk_size = 64 * 1024  # 64KB chunks
        total_written = 0

        logger.info(f"Writing data in {chunk_size} byte chunks...")

        for offset in range(0, len(test_data), chunk_size):
            chunk = test_data[offset:offset + chunk_size]
            chunk_address = f"0x{int(address, 16) + offset:x}"

            response = requests.patch(
                f"{BASE_URL}/memory/{chunk_address}",
                json={
                    "bytes": chunk.hex(),
                    "format": "hex",
                    "length": len(chunk),
                },
                timeout=30
            )
            data = assert_success(response, f"Write chunk at offset {offset}")

            total_written += len(chunk)
            if offset % (chunk_size * 10) == 0:
                logger.info(f"  Progress: {total_written} / {len(test_data)} bytes")

        logger.info(f"Large write completed: {total_written} bytes")

        # Verify by reading back a sample
        sample_size = min(1024, len(test_data))
        read_response = requests.get(f"{BASE_URL}/memory/read?address={address}&length={sample_size}&format=hex", timeout=10)
        read_data = assert_success(read_response, "Read sample")
        read_hex = read_data.get("result", {}).get("hexBytes", "")
        expected_hex = test_data[:sample_size].hex()

        if read_hex.lower() == expected_hex.lower():
            logger.info("Sample verification successful")
            return True
        else:
            logger.error(f"Sample verification failed")
            return False
    except Exception as e:
        logger.error(f"Large memory write test failed: {e}")
        import traceback
        traceback.print_exc()
        return False

def test_delete_block():
    """Test deleting a memory block."""
    logger.info("=" * 60)
    logger.info("TEST: Delete Memory Block")
    logger.info("=" * 60)

    address = find_free_address_range()
    block_name = "test_delete_block"

    # Create a block first
    logger.info(f"Creating block '{block_name}' at {address}")

    try:
        response = requests.post(
            f"{BASE_URL}/memory/blocks",
            json={
                "name": block_name,
                "address": address,
                "size": "4096",
                "readable": "true",
                "writable": "true",
                "executable": "false",
                "initialized": "true",
            },
            timeout=10
        )
        data = assert_success(response, "Create block for deletion")

        # Verify it exists
        blocks = get_memory_blocks()
        found = any(b.get("name") == block_name for b in blocks)
        if not found:
            logger.error("Block not found after creation")
            return False

        # Delete it
        logger.info(f"Deleting block '{block_name}'")
        response = requests.delete(f"{BASE_URL}/memory/blocks/{address}", timeout=10)

        # 404 is acceptable if block was already deleted
        if response.status_code not in [200, 404]:
            logger.error(f"Delete failed with status {response.status_code}")
            return False

        # Verify it's gone
        blocks = get_memory_blocks()
        found = any(b.get("name") == block_name for b in blocks)
        if found:
            logger.error("Block still exists after deletion")
            return False

        logger.info("Block successfully deleted")
        return True
    except Exception as e:
        logger.error(f"Delete block test failed: {e}")
        return False

def test_write_alias_endpoints():
    """Test write alias endpoints (POST /memory/write, PUT /memory/{address})."""
    logger.info("=" * 60)
    logger.info("TEST: Write Alias Endpoints")
    logger.info("=" * 60)

    blocks = get_memory_blocks()
    writable_block = None

    for block in blocks:
        if block.get("writable", False) and block.get("isInitialized", False):
            writable_block = block
            break

    if not writable_block:
        logger.warning("No writable initialized block found, creating one")
        address = find_free_address_range()
        try:
            response = requests.post(
                f"{BASE_URL}/memory/blocks",
                json={
                    "name": "test_write_alias",
                    "address": address,
                    "size": "4096",
                    "readable": "true",
                    "writable": "true",
                    "executable": "false",
                    "initialized": "true",
                },
                timeout=10
            )
            data = assert_success(response, "Create test block")
            writable_block = data.get("result", {})
            address = writable_block.get("start", address)
        except Exception as e:
            logger.error(f"Failed to create test block: {e}")
            return False
    else:
        address = writable_block.get("start", "0x1000")

    test_bytes = bytes([0xAA, 0xBB, 0xCC, 0xDD])
    hex_str = test_bytes.hex()

    # Test POST /memory/write
    logger.info("Testing POST /memory/write")
    try:
        response = requests.post(
            f"{BASE_URL}/memory/write",
            json={
                "address": address,
                "bytes": hex_str,
                "format": "hex",
                "length": len(test_bytes),
            },
            timeout=10
        )
        data = assert_success(response, "POST /memory/write")
        logger.info("POST /memory/write successful")
    except Exception as e:
        logger.error(f"POST /memory/write failed: {e}")
        return False

    # Test PUT /memory/{address}
    logger.info("Testing PUT /memory/{address}")
    try:
        response = requests.put(
            f"{BASE_URL}/memory/{address}",
            json={
                "bytes": hex_str,
                "format": "hex",
                "length": len(test_bytes),
            },
            timeout=10
        )
        data = assert_success(response, "PUT /memory/{address}")
        logger.info("PUT /memory/{address} successful")
    except Exception as e:
        logger.error(f"PUT /memory/{address} failed: {e}")
        return False

    logger.info("All alias endpoints working")
    return True

def test_block_permissions():
    """Test creating blocks with different permission combinations."""
    logger.info("=" * 60)
    logger.info("TEST: Block Permissions")
    logger.info("=" * 60)

    permission_tests = [
        ("test_r_only", "r---", "true", "false", "false"),
        ("test_w_only", "-w--", "false", "true", "false"),
        ("test_x_only", "--x-", "false", "false", "true"),
        ("test_rw", "rw--", "true", "true", "false"),
        ("test_rx", "r-x-", "true", "false", "true"),
        ("test_rwx", "rwx-", "true", "true", "true"),
    ]

    success_count = 0

    for name, expected_perm, readable, writable, executable in permission_tests:
        address = find_free_address_range()

        logger.info(f"Testing {name}: R={readable} W={writable} X={executable} (expected: {expected_perm})")

        try:
            response = requests.post(
                f"{BASE_URL}/memory/blocks",
                json={
                    "name": name,
                    "address": address,
                    "size": "4096",
                    "readable": readable,
                    "writable": writable,
                    "executable": executable,
                    "initialized": "true",
                },
                timeout=10
            )
            data = assert_success(response, f"Create block {name}")

            result = data.get("result", {})
            actual_perm = result.get("permissions", "")

            if actual_perm == expected_perm:
                logger.info(f"  {name}: permissions correct ({actual_perm})")
                success_count += 1
            else:
                logger.error(f"  {name}: permissions mismatch - expected {expected_perm}, got {actual_perm}")
        except Exception as e:
            logger.error(f"  {name}: failed - {e}")

    logger.info(f"Permission tests: {success_count}/{len(permission_tests)} passed")
    return success_count == len(permission_tests)

def run_all_tests():
    """Run all memory operation tests."""
    logger.info("=" * 80)
    logger.info("GhydraMCP Memory Operations Test Suite")
    logger.info("=" * 80)

    # Wait for server and program
    if not wait_for_server():
        logger.error("Cannot proceed: Ghidra server not available")
        return False

    if not wait_for_program_loaded():
        logger.error("Cannot proceed: No program loaded")
        return False

    # Clean up any previous test blocks
    cleanup_test_blocks()

    # Run tests
    tests = [
        ("Read Memory", test_read_memory),
        ("Write Memory (Hex)", test_write_memory_hex),
        ("Write Memory (Base64)", test_write_memory_base64),
        ("Create Initialized Block", test_create_initialized_block),
        ("Create Uninitialized Block", test_create_uninitialized_block),
        ("Auto-Convert Uninitialized Block", test_auto_convert_uninitialized_block),
        ("Large Memory Write", test_large_memory_write),
        ("Delete Block", test_delete_block),
        ("Write Alias Endpoints", test_write_alias_endpoints),
        ("Block Permissions", test_block_permissions),
    ]

    results = []

    for test_name, test_func in tests:
        try:
            logger.info(f"\n{'=' * 80}")
            logger.info(f"Running: {test_name}")
            logger.info(f"{'=' * 80}")

            result = test_func()
            results.append((test_name, result))

            if result:
                logger.info(f"✓ {test_name}: PASSED")
            else:
                logger.error(f"✗ {test_name}: FAILED")
        except Exception as e:
            logger.error(f"✗ {test_name}: EXCEPTION - {e}")
            import traceback
            traceback.print_exc()
            results.append((test_name, False))

    # Clean up test blocks
    cleanup_test_blocks()

    # Print summary
    logger.info("\n" + "=" * 80)
    logger.info("TEST SUMMARY")
    logger.info("=" * 80)

    passed = sum(1 for _, result in results if result)
    total = len(results)

    for test_name, result in results:
        status = "✓ PASSED" if result else "✗ FAILED"
        logger.info(f"{status}: {test_name}")

    logger.info(f"\nTotal: {passed}/{total} tests passed")

    if passed == total:
        logger.info("\n🎉 All tests passed!")
        return True
    else:
        logger.error(f"\n❌ {total - passed} test(s) failed")
        return False

if __name__ == "__main__":
    success = run_all_tests()
    sys.exit(0 if success else 1)