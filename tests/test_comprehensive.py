#!/usr/bin/env python3
"""
Comprehensive test suite for GhydraMCP API endpoints.

This test suite covers all API endpoints and error codes to ensure
complete functionality and proper error handling.
"""

import json
import sys
import urllib.request
import urllib.error
from typing import Dict, List, Tuple, Any

# Configuration
HOST = "localhost"
PORT = 8193
BASE_URL = f"http://{HOST}:{PORT}"

# Test results tracking
test_results = {
    "passed": 0,
    "failed": 0,
    "skipped": 0,
    "errors": []
}

def print_header(title: str):
    """Print a formatted header."""
    print("\n" + "=" * 70)
    print(title)
    print("=" * 70)

def print_test(name: str, passed: bool, message: str = ""):
    """Print test result."""
    status = "✓ PASS" if passed else "✗ FAIL"
    print(f"  {status}: {name}")
    if message:
        print(f"    {message}")

    if passed:
        test_results["passed"] += 1
    else:
        test_results["failed"] += 1
        test_results["errors"].append((name, message))

def print_skip(name: str, reason: str):
    """Print skipped test."""
    print(f"  ⊘ SKIP: {name} - {reason}")
    test_results["skipped"] += 1

def api_request(method: str, path: str, data: Dict = None,
                expected_status: int = 200) -> Tuple[bool, Dict, int]:
    """
    Make an API request and return success, response data, and status code.

    Args:
        method: HTTP method (GET, POST, PUT, PATCH, DELETE)
        path: API path (e.g., "/memory/blocks")
        data: Request body data (will be JSON encoded)
        expected_status: Expected HTTP status code

    Returns:
        Tuple of (success, response_data, status_code)
    """
    url = f"{BASE_URL}{path}"

    try:
        if data is not None:
            body = json.dumps(data).encode()
            req = urllib.request.Request(
                url,
                data=body,
                headers={"Content-Type": "application/json"},
                method=method
            )
        else:
            req = urllib.request.Request(url, method=method)

        with urllib.request.urlopen(req) as resp:
            response_data = json.loads(resp.read().decode())
            return (True, response_data, resp.status)

    except urllib.error.HTTPError as e:
        try:
            response_data = json.loads(e.read().decode())
        except:
            response_data = {"error": str(e)}
        return (False, response_data, e.code)

    except Exception as e:
        return (False, {"error": str(e)}, 0)

def test_server_status():
    """Test server status endpoint."""
    print_header("Server Status Tests")

    # Test server status
    success, data, status = api_request("GET", "/server/status")
    print_test("Server status endpoint", success and status == 200,
              f"Status: {status}, Data: {data.get('result', {})}")

    # Test capabilities
    success, data, status = api_request("GET", "/capabilities")
    print_test("Capabilities endpoint", success and status == 200,
              f"Status: {status}")

def test_program_info():
    """Test program information endpoints."""
    print_header("Program Information Tests")

    # Test program info
    success, data, status = api_request("GET", "/program")
    print_test("Program info endpoint", success and status == 200,
              f"Status: {status}")

    # Test current address
    success, data, status = api_request("GET", "/address")
    print_test("Current address endpoint", success and status == 200,
              f"Status: {status}")

    # Test current function
    success, data, status = api_request("GET", "/function")
    print_test("Current function endpoint", success and status == 200,
              f"Status: {status}")

def test_memory_operations():
    """Test memory operation endpoints."""
    print_header("Memory Operations Tests")

    test_addr = "0x90000000"
    test_name = "test_api_comprehensive"
    test_size = 4096

    # Test create memory block
    success, data, status = api_request("POST", "/memory/blocks", {
        "name": test_name,
        "address": test_addr,
        "size": str(test_size),
        "readable": "true",
        "writable": "true",
        "executable": "false",
        "initialized": "true"
    })
    print_test("Create memory block", status in [200, 201, 409],
              f"Status: {status}, Name: {test_name}")

    # Test list memory blocks
    success, data, status = api_request("GET", "/memory/blocks")
    print_test("List memory blocks", success and status == 200,
              f"Status: {status}, Count: {len(data.get('result', []))}")

    # Test read memory
    success, data, status = api_request("GET", f"/memory?address={test_addr}&length=16")
    print_test("Read memory", success and status == 200,
              f"Status: {status}")

    # Test write memory
    test_bytes = bytes([0xDE, 0xAD, 0xBE, 0xEF])
    success, data, status = api_request("PATCH", f"/memory/{test_addr}", {
        "bytes": test_bytes.hex(),
        "format": "hex"
    })
    print_test("Write memory", success and status == 200,
              f"Status: {status}, Bytes written: {data.get('result', {}).get('bytesWritten', 0)}")

    # Test write to read-only block
    ro_name = f"{test_name}_ro"
    ro_addr = "0x90001000"
    success, data, status = api_request("POST", "/memory/blocks", {
        "name": ro_name,
        "address": ro_addr,
        "size": str(test_size),
        "readable": "true",
        "writable": "false",
        "executable": "false",
        "initialized": "true"
    })
    print_test("Create read-only block", status in [200, 201, 409],
              f"Status: {status}")

    # Write to read-only block (should succeed with autoMadeWritable)
    success, data, status = api_request("PATCH", f"/memory/{ro_addr}", {
        "bytes": test_bytes.hex(),
        "format": "hex"
    })
    print_test("Write to read-only block", success and status == 200,
              f"Status: {status}, autoMadeWritable: {data.get('result', {}).get('autoMadeWritable', False)}")

    # Test memory map
    success, data, status = api_request("GET", "/memory/map")
    print_test("Memory map endpoint", success and status == 200,
              f"Status: {status}")

def test_functions():
    """Test function endpoints."""
    print_header("Function Tests")

    # Test list functions
    success, data, status = api_request("GET", "/functions")
    print_test("List functions", success and status == 200,
              f"Status: {status}, Count: {len(data.get('result', []))}")

    # Test get function by address
    success, data, status = api_request("GET", "/functions/0x1000")
    print_test("Get function by address", status in [200, 404],
              f"Status: {status}")

    # Test get function by name
    success, data, status = api_request("GET", "/functions/by-name/entry")
    print_test("Get function by name", status in [200, 404],
              f"Status: {status}")

def test_data_operations():
    """Test data operation endpoints."""
    print_header("Data Operations Tests")

    test_addr = "0x90002000"
    test_name = "test_data_api"

    # Create test memory block
    api_request("POST", "/memory/blocks", {
        "name": test_name,
        "address": test_addr,
        "size": "4096",
        "readable": "true",
        "writable": "true",
        "executable": "false",
        "initialized": "true"
    })

    # Test create data
    success, data, status = api_request("POST", "/data", {
        "address": test_addr,
        "type": "byte"
    })
    print_test("Create data", success and status == 200,
              f"Status: {status}")

    # Test list data
    success, data, status = api_request("GET", "/data")
    print_test("List data", success and status == 200,
              f"Status: {status}")

    # Test update data type
    success, data, status = api_request("POST", "/data/type", {
        "address": test_addr,
        "type": "dword"
    })
    print_test("Update data type", success and status == 200,
              f"Status: {status}")

    # Test delete data
    success, data, status = api_request("POST", "/data/delete", {
        "address": test_addr
    })
    print_test("Delete data", success and status == 200,
              f"Status: {status}")

    # Test strings
    success, data, status = api_request("GET", "/strings")
    print_test("List strings", success and status == 200,
              f"Status: {status}")

def test_symbols():
    """Test symbol endpoints."""
    print_header("Symbol Tests")

    # Test list symbols
    success, data, status = api_request("GET", "/symbols")
    print_test("List symbols", success and status == 200,
              f"Status: {status}")

    # Test list imports
    success, data, status = api_request("GET", "/symbols/imports")
    print_test("List imports", success and status == 200,
              f"Status: {status}")

    # Test list exports
    success, data, status = api_request("GET", "/symbols/exports")
    print_test("List exports", success and status == 200,
              f"Status: {status}")

def test_structs():
    """Test struct endpoints."""
    print_header("Struct Tests")

    test_struct_name = "test_api_struct"

    # Test list structs
    success, data, status = api_request("GET", "/structs")
    print_test("List structs", success and status == 200,
              f"Status: {status}")

    # Test create struct
    success, data, status = api_request("POST", "/structs/create", {
        "name": test_struct_name,
        "category": "/test"
    })
    print_test("Create struct", success and status == 200,
              f"Status: {status}")

    # Test add field to struct
    success, data, status = api_request("POST", "/structs/addfield", {
        "struct": test_struct_name,
        "fieldName": "field1",
        "fieldType": "int",
        "offset": "0"
    })
    print_test("Add field to struct", success and status == 200,
              f"Status: {status}")

    # Test update field
    success, data, status = api_request("POST", "/structs/updatefield", {
        "struct": test_struct_name,
        "fieldName": "field1",
        "fieldType": "uint"
    })
    print_test("Update struct field", success and status == 200,
              f"Status: {status}")

    # Test delete struct
    success, data, status = api_request("POST", "/structs/delete", {
        "struct": test_struct_name
    })
    print_test("Delete struct", success and status == 200,
              f"Status: {status}")

def test_datatypes():
    """Test datatype endpoints."""
    print_header("Datatype Tests")

    # Test list datatypes
    success, data, status = api_request("GET", "/datatypes")
    print_test("List datatypes", success and status == 200,
              f"Status: {status}")

    # Test create struct datatype
    success, data, status = api_request("POST", "/datatypes/struct", {
        "name": "test_api_datatype_struct",
        "category": "test"
    })
    print_test("Create struct datatype", success and status == 200,
              f"Status: {status}")

    # Test create enum datatype
    success, data, status = api_request("POST", "/datatypes/enum", {
        "name": "test_api_datatype_enum",
        "category": "test"
    })
    print_test("Create enum datatype", success and status == 200,
              f"Status: {status}")

    # Test create union datatype
    success, data, status = api_request("POST", "/datatypes/union", {
        "name": "test_api_datatype_union",
        "category": "test"
    })
    print_test("Create union datatype", success and status == 200,
              f"Status: {status}")

def test_segments():
    """Test segment endpoints."""
    print_header("Segment Tests")

    # Test list segments
    success, data, status = api_request("GET", "/segments")
    print_test("List segments", success and status == 200,
              f"Status: {status}")

def test_namespaces():
    """Test namespace endpoints."""
    print_header("Namespace Tests")

    # Test list namespaces
    success, data, status = api_request("GET", "/namespaces")
    print_test("List namespaces", success and status == 200,
              f"Status: {status}")

def test_classes():
    """Test class endpoints."""
    print_header("Class Tests")

    # Test list classes
    success, data, status = api_request("GET", "/classes")
    print_test("List classes", success and status == 200,
              f"Status: {status}")

def test_variables():
    """Test variable endpoints."""
    print_header("Variable Tests")

    # Test list global variables
    success, data, status = api_request("GET", "/variables")
    print_test("List global variables", success and status == 200,
              f"Status: {status}")

def test_instances():
    """Test instance endpoints."""
    print_header("Instance Tests")

    # Test list instances
    success, data, status = api_request("GET", "/instances")
    print_test("List instances", success and status == 200,
              f"Status: {status}")

def test_analysis():
    """Test analysis endpoints."""
    print_header("Analysis Tests")

    # Test analysis status
    success, data, status = api_request("GET", "/analysis/status")
    print_test("Analysis status", success and status == 200,
              f"Status: {status}")

    # Test run analysis (skip to avoid long running time)
    print_skip("Run analysis", "Skipped to avoid long running time")

def test_xrefs():
    """Test cross-reference endpoints."""
    print_header("Cross-Reference Tests")

    # Test xrefs endpoint
    success, data, status = api_request("GET", "/xrefs?to_addr=0x1000")
    print_test("Get cross-references", success and status == 200,
              f"Status: {status}")

def test_error_codes():
    """Test various error codes."""
    print_header("Error Code Tests")

    # Test invalid address
    success, data, status = api_request("GET", "/memory/invalid_address")
    print_test("Invalid address error", not success and status == 400,
              f"Status: {status}, Error: {data.get('error', {}).get('code', 'N/A')}")

    # Test missing parameter
    success, data, status = api_request("POST", "/memory/blocks", {})
    print_test("Missing parameter error", not success and status == 400,
              f"Status: {status}, Error: {data.get('error', {}).get('code', 'N/A')}")

    # Test not found
    success, data, status = api_request("GET", "/functions/0x99999999")
    print_test("Not found error", not success and status == 404,
              f"Status: {status}, Error: {data.get('error', {}).get('code', 'N/A')}")

    # Test method not allowed
    success, data, status = api_request("POST", "/program", {})
    print_test("Method not allowed error", not success and status == 405,
              f"Status: {status}, Error: {data.get('error', {}).get('code', 'N/A')}")

def print_summary():
    """Print test summary."""
    print_header("Test Summary")
    print(f"  Total:  {test_results['passed'] + test_results['failed'] + test_results['skipped']}")
    print(f"  Passed: {test_results['passed']}")
    print(f"  Failed: {test_results['failed']}")
    print(f"  Skipped: {test_results['skipped']}")

    if test_results['failed'] > 0:
        print("\nFailed tests:")
        for name, message in test_results['errors']:
            print(f"  - {name}: {message}")

    print("\n" + "=" * 70)
    if test_results['failed'] == 0:
        print("✓ All tests passed!")
    else:
        print(f"✗ {test_results['failed']} test(s) failed")
    print("=" * 70)

def main():
    """Run all tests."""
    print_header("GhydraMCP Comprehensive API Test Suite")
    print(f"Testing API at: {BASE_URL}")

    try:
        # Run all test suites
        test_server_status()
        test_program_info()
        test_memory_operations()
        test_functions()
        test_data_operations()
        test_symbols()
        test_structs()
        test_datatypes()
        test_segments()
        test_namespaces()
        test_classes()
        test_variables()
        test_instances()
        test_analysis()
        test_xrefs()
        test_error_codes()

        # Print summary
        print_summary()

        # Return exit code
        return 0 if test_results['failed'] == 0 else 1

    except Exception as e:
        print(f"\n[FATAL] Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    sys.exit(main())