# GhydraMCP Writable Fix

## Problem
The API was unable to write data when a segment is not writable in Ghidra. This was blocking the ability to map and write to read-only segments like a loader would do.

## Solution
Modified the `writeMemory` method in `MemoryEndpoints.java` to allow writes to non-writable blocks by:

1. **Removing the 403 error check** for non-writable blocks
2. **Temporarily making the block writable** using reflection to call `setPermissions()`
3. **Performing the write** using `memory.setBytes()`
4. **Restoring the original permissions** after the write

## Changes Made

### 1. MemoryEndpoints.java

#### Removed non-writable block check (lines ~1395-1408)
```java
// OLD CODE (REMOVED):
if (!block.isWrite()) {
    // ... send 403 error ...
    return;
}
```

#### Added writable tracking (lines ~1395-1400)
```java
// NEW CODE:
// For a loader-like API, allow writes to non-writable blocks by
// temporarily making them writable, performing the write, and restoring permissions.
// This is necessary because we may be mapping segments with no write permissions
// but still need to write data like a loader would do.
final boolean wasNotWritable = !block.isWrite();
final boolean originalWrite = block.isWrite();
final boolean originalRead = block.isRead();
final boolean originalExecute = block.isExecute();
```

#### Updated performWriteInTransaction signature (line ~1482)
```java
// OLD:
private void performWriteInTransaction(Program program,
                                       Memory memory,
                                       Address address,
                                       byte[] bytes,
                                       boolean clearCodeUnits) throws Exception

// NEW:
private void performWriteInTransaction(Program program,
                                       Memory memory,
                                       Address address,
                                       byte[] bytes,
                                       boolean clearCodeUnits,
                                       MemoryBlock block,
                                       boolean wasNotWritable) throws Exception
```

#### Added permission manipulation in performWriteInTransaction (lines ~1485-1515)
```java
// NEW CODE:
TransactionHelper.executeInTransaction(program,
    clearCodeUnits ? "Force Write Memory (clear code units)" : "Write Memory",
    () -> {
        // Temporarily make the block writable if it wasn't already
        if (wasNotWritable) {
            try {
                Method m = block.getClass().getMethod("setPermissions", boolean.class, boolean.class, boolean.class);
                m.invoke(block, block.isRead(), true, block.isExecute());
            } catch (Exception e) {
                throw new Exception("Failed to make block writable: " + e.getMessage(), e);
            }
        }
        if (clearCodeUnits) {
            Address end = address.addNoWrap(bytes.length - 1);
            program.getListing().clearCodeUnits(address, end, false);
        }
        memory.setBytes(address, bytes);
        // Restore original permissions after write
        if (wasNotWritable) {
            try {
                Method m = block.getClass().getMethod("setPermissions", boolean.class, boolean.class, boolean.class);
                m.invoke(block, block.isRead(), false, block.isExecute());
            } catch (Exception e) {
                // Log but don't fail the write if restore fails
                Msg.error(MemoryEndpoints.class, "Failed to restore block permissions: " + e.getMessage());
            }
        }
        return true;
    });
```

#### Added autoMadeWritable tracking (lines ~1440-1445)
```java
// NEW CODE:
boolean autoClearedCodeUnits = false;
boolean autoMadeWritable = false;
try {
    performWriteInTransaction(program, memory, address, bytes, force, block, wasNotWritable);
    autoMadeWritable = wasNotWritable;
} catch (Exception e) {
    // ...
}
```

#### Added autoMadeWritable to response (line ~1465)
```java
// NEW CODE:
result.put("autoMadeWritable", autoMadeWritable);
```

## Testing

Created `test_memory_writable.py` to verify that:
1. Writes to read-only blocks succeed
2. The `autoMadeWritable` flag is set to `true` in the response
3. Data is correctly written to the block
4. The block remains read-only after the write

## Behavior

### Before
- Writing to a read-only block returned HTTP 403 with error code `MEMORY_BLOCK_NOT_WRITABLE`
- No way to write to read-only segments via the API

### After
- Writing to a read-only block succeeds with HTTP 200
- Response includes `autoMadeWritable: true` to indicate the block was temporarily made writable
- Block permissions are restored after the write
- Works like a loader: can map and write to any segment regardless of original permissions

## Example

```bash
# Create a read-only block
curl -X POST http://localhost:8193/memory/blocks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test_ro",
    "address": "0x80000000",
    "size": "4096",
    "readable": "true",
    "writable": "false",
    "executable": "false",
    "initialized": "true"
  }'

# Write to the read-only block (succeeds with autoMadeWritable=true)
curl -X PATCH http://localhost:8193/memory/0x80000000 \
  -H "Content-Type: application/json" \
  -d '{
    "bytes": "deadbeef",
    "format": "hex"
  }'

# Response:
# {
#   "success": true,
#   "result": {
#     "address": "0x80000000",
#     "length": 4,
#     "bytesWritten": 4,
#     "autoMadeWritable": true,
#     "hexBytes": "deadbeef"
#   }
# }
```

## Reload Instructions

The plugin has been rebuilt. To apply the changes:

1. In Ghidra, go to File -> Configure -> Extensions
2. Find 'GhydraMCP' in the list
3. Click 'Remove' to uninstall the old version
4. Click 'Add Extension File' and select:
   `/home/eljakim/Source/GhydraMCP/target/GhydraMCP-Complete-d045f91-20260514-151343.zip`
5. Click 'OK' to install the new version
6. Restart Ghidra

After reloading, run: `python3 test_memory_writable.py`