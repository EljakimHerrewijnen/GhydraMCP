package eu.starsong.ghidra.endpoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import eu.starsong.ghidra.util.TransactionHelper;
import ghidra.app.plugin.core.colorizer.ColorizingService;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.mem.MemoryConflictException;
import ghidra.program.model.address.AddressOverflowException;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.listing.Program;
import ghidra.framework.plugintool.PluginTool;
import ghidra.util.Msg;
import ghidra.util.task.TaskMonitor;

import java.io.IOException;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

public class MemoryEndpoints extends AbstractEndpoint {

    private static final int DEFAULT_MEMORY_LENGTH = 16;
    private static final int MAX_MEMORY_LENGTH = 64 * 1024 * 1024; // 64 MB
    private static final String MEMORY_BLOCK_MODE_CREATE = "create";
    private static final String MEMORY_BLOCK_MODE_CREATE_OR_UPDATE = "create_or_update";
    private PluginTool tool;

    private static final class MemoryBlockRequest {
        private final String name;
        private final Address start;
        private final long size;
        private final Address end;
        private final boolean readable;
        private final boolean writable;
        private final boolean executable;
        private final boolean initialized;
        private final String mode;

        private MemoryBlockRequest(String name,
                                   Address start,
                                   long size,
                                   Address end,
                                   boolean readable,
                                   boolean writable,
                                   boolean executable,
                                   boolean initialized,
                                   String mode) {
            this.name = name;
            this.start = start;
            this.size = size;
            this.end = end;
            this.readable = readable;
            this.writable = writable;
            this.executable = executable;
            this.initialized = initialized;
            this.mode = mode;
        }
    }

    public MemoryEndpoints(Program program, int port) {
        super(program, port);
    }

    public MemoryEndpoints(Program program, int port, PluginTool tool) {
        super(program, port);
        this.tool = tool;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        // Per HttpServer docs: paths are matched by longest matching prefix
        // So register specific endpoints first, then more general ones

        // Comments endpoint path needs to be registered with a specific context path
        // Example: /memory/0x1000/comments/plate needs a specific handler
        server.createContext("/memory/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/comments/")) {
                handleMemoryAddressRequest(exchange);
            } else if (path.equals("/memory/write") || path.equals("/memory/write/")) {
                handleMemoryWriteAlias(exchange);
            } else if (path.equals("/memory/map") || path.equals("/memory/map/")) {
                handleMemoryBlocksRequest(exchange);
            } else if (path.equals("/memory/background-colors")) {
                handleBackgroundColorsRequest(exchange);
            } else if (path.equals("/memory/blocks") || path.equals("/memory/blocks/") || path.startsWith("/memory/blocks/")) {
                handleMemoryBlocksRequest(exchange);
            } else {
                // Handle as general memory address request
                handleMemoryAddressRequest(exchange);
            }
        });

        // Register the most general endpoint last
        server.createContext("/memory", this::handleMemoryRequest);
    }

    private void handleMemoryRequest(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                Map<String, String> qparams = parseQueryParams(exchange);
                String addressStr = qparams.get("address");
                String lengthStr = qparams.get("length");

                // Create ResponseBuilder for HATEOAS-compliant response
                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true)
                    .addLink("self", "/memory" + (exchange.getRequestURI().getRawQuery() != null ?
                        "?" + exchange.getRequestURI().getRawQuery() : ""));

                // Add common links
                builder.addLink("program", "/program");
                builder.addLink("blocks", "/memory/blocks");

                Program program = getCurrentProgram();
                if (program == null) {
                    sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                    return;
                }

                if (addressStr == null || addressStr.isEmpty()) {
                    sendErrorResponse(exchange, 400, "Address parameter is required", "MISSING_PARAMETER");
                    return;
                }

                // Parse length parameter
                int length = DEFAULT_MEMORY_LENGTH;
                int requestedLength = length;
                boolean lengthCapped = false;
                if (lengthStr != null && !lengthStr.isEmpty()) {
                    try {
                        length = Integer.parseInt(lengthStr);
                        requestedLength = length;
                        if (length <= 0) {
                            sendErrorResponse(exchange, 400, "Length must be positive", "INVALID_PARAMETER");
                            return;
                        }
                        if (length > MAX_MEMORY_LENGTH) {
                            Msg.warn(this, "Requested memory read length " + length +
                                     " exceeds maximum " + MAX_MEMORY_LENGTH + ", capping to " + MAX_MEMORY_LENGTH);
                            length = MAX_MEMORY_LENGTH;
                            lengthCapped = true;
                        }
                    } catch (NumberFormatException e) {
                        sendErrorResponse(exchange, 400, "Invalid length parameter", "INVALID_PARAMETER");
                        return;
                    }
                }

                // Parse address with safety fallbacks
                AddressFactory addressFactory = program.getAddressFactory();
                Address address;
                try {
                    // Try to use provided address
                    address = addressFactory.getAddress(addressStr);
                } catch (Exception e) {
                    try {
                        // If there's an exception, try to get the image base address instead
                        address = program.getImageBase();
                        Msg.warn(this, "Invalid address format. Using image base address: " + address);
                    } catch (Exception e2) {
                        // If image base fails, use min address from default space
                        address = addressFactory.getDefaultAddressSpace().getMinAddress();
                        Msg.warn(this, "Could not get image base. Using default address: " + address);
                    }
                }

                // Read memory
                Memory memory = program.getMemory();
                if (!memory.contains(address)) {
                    // Try to find a valid memory block
                    MemoryBlock[] blocks = memory.getBlocks();
                    if (blocks.length > 0) {
                        // Use the first memory block
                        address = blocks[0].getStart();
                        Msg.info(this, "Using first memory block address: " + address);
                    } else {
                        sendErrorResponse(exchange, 404, "No valid memory blocks found", "NO_MEMORY_BLOCKS");
                        return;
                    }
                }

                try {
                    // Read bytes
                    byte[] bytes = new byte[length];
                    int bytesRead = memory.getBytes(address, bytes, 0, length);

                    // Format as hex string (continuous, no spaces - Python bridge parses by pairs)
                    StringBuilder hexString = new StringBuilder();
                    for (int i = 0; i < bytesRead; i++) {
                        String hex = Integer.toHexString(bytes[i] & 0xFF).toUpperCase();
                        if (hex.length() == 1) {
                            hexString.append('0');
                        }
                        hexString.append(hex);
                    }

                    // Build result object
                    Map<String, Object> result = new HashMap<>();
                    result.put("address", address.toString());
                    result.put("bytesRead", bytesRead);
                    result.put("hexBytes", hexString.toString());
                    result.put("rawBytes", Base64.getEncoder().encodeToString(bytes));
                    if (lengthCapped) {
                        result.put("warning", "Requested length " + requestedLength +
                                   " exceeds maximum " + MAX_MEMORY_LENGTH + "; result was capped");
                    }

                    // Add next/prev links
                    builder.addLink("next", "/memory?address=" + address.add(length) + "&length=" + length);
                    if (address.getOffset() >= length) {
                        builder.addLink("prev", "/memory?address=" + address.subtract(length) + "&length=" + length);
                    }

                    // Add result and send response
                    builder.result(result);
                    sendJsonResponse(exchange, builder.build(), 200);

                } catch (MemoryAccessException e) {
                    sendErrorResponse(exchange, 404, "Cannot read memory at address: " + e.getMessage(), "MEMORY_ACCESS_ERROR");
                }

            } else if ("PATCH".equals(method) || "PUT".equals(method)) {
                Map<String, String> qparams = parseQueryParams(exchange);
                String addressStr = qparams.get("address");
                if (addressStr == null || addressStr.isEmpty()) {
                    Object addrAttr = exchange.getAttribute("address");
                    if (addrAttr != null) {
                        addressStr = addrAttr.toString();
                    }
                }
                if (addressStr == null || addressStr.isEmpty()) {
                    sendErrorResponse(exchange, 400, "Address parameter is required", "MISSING_PARAMETER");
                    return;
                }

                Map<String, String> payload = parseJsonPostParams(exchange);
                writeMemory(exchange, addressStr, payload, "/memory/" + addressStr);
            } else {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            Msg.error(this, "Error in /memory endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            String h = Integer.toHexString(data[i] & 0xFF).toUpperCase();
            if (h.length() == 1) sb.append('0');
            sb.append(h);
            if (i < data.length - 1) sb.append(' ');
        }
        return sb.toString();
    }

    private byte[] decodeBytes(String data, String format) {
        switch (format) {
            case "hex":
                return parseHex(data);
            case "base64":
                try {
                    return Base64.getDecoder().decode(data);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid base64 data");
                }
            case "string":
                return data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    private byte[] parseHex(String hex) {
        String cleaned = hex.replaceAll("[\n\r\t ]", "");
        if (cleaned.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string must have even length");
        }
        int len = cleaned.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            try {
                out[i / 2] = (byte) Integer.parseInt(cleaned.substring(i, i + 2), 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid hex at position " + i);
            }
        }
        return out;
    }

    /**
 * Handle requests to /memory/{address} including child resources like comments
 */
private void handleMemoryAddressRequest(HttpExchange exchange) throws IOException {
    try {
        // Extract address from path: /memory/{address}/...
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/memory/") || path.equals("/memory")) {
            handleMemoryRequest(exchange);
            return;
        }

        // Parse address from path
        String remainingPath = path.substring("/memory/".length());

        // Check if this is a request for a specific address's comments
        if (remainingPath.contains("/comments/")) {
            // Format: /memory/{address}/comments/{comment_type}
            String[] parts = remainingPath.split("/comments/", 2);
            String addressStr = parts[0];
            String commentType = parts.length > 1 ? parts[1] : "plate"; // Default to plate comments

            handleMemoryComments(exchange, addressStr, commentType);
            return;
        }

        // Check if this is a request for a specific address background color
        if (remainingPath.contains("/background-color")) {
            String addressStr = remainingPath.split("/background-color", 2)[0];
            handleAddressBackgroundColor(exchange, addressStr);
            return;
        }

        // Check if this is a disassembly request
        if (remainingPath.contains("/disassembly")) {
            String addressStr = remainingPath.split("/disassembly")[0];
            handleDisassemblyAtAddress(exchange, addressStr);
            return;
        }

        // Otherwise, treat as a direct memory request with address in the path
        String addressStr = remainingPath;
        if ("PATCH".equals(exchange.getRequestMethod()) || "PUT".equals(exchange.getRequestMethod())) {
            Map<String, String> payload = parseJsonPostParams(exchange);
            writeMemory(exchange, addressStr, payload, "/memory/" + addressStr);
            return;
        }

        Map<String, String> params = parseQueryParams(exchange);

        // Handle same as the query parameter version
        params.put("address", addressStr);
        exchange.setAttribute("address", addressStr);

        // Delegate to the main memory handler
        handleMemoryRequest(exchange);
    } catch (Exception e) {
        Msg.error(this, "Error handling memory address endpoint", e);
        sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage(), "INTERNAL_ERROR");
    }
}

/**
 * Handle setting/clearing background color at a specific memory address.
 * Endpoint: /memory/{address}/background-color
 */
private void handleAddressBackgroundColor(HttpExchange exchange, String addressStr) throws IOException {
    try {
        String method = exchange.getRequestMethod();
        Program program = getCurrentProgram();

        if (program == null) {
            sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
            return;
        }

        Address address;
        try {
            address = program.getAddressFactory().getAddress(addressStr);
        } catch (Exception e) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("address", addressStr);
            details.put("required_action", "use a valid program address");
            sendDetailedErrorResponse(exchange, 400,
                "Invalid address format: " + addressStr,
                "INVALID_ADDRESS",
                details);
            return;
        }

        if (!program.getMemory().contains(address)) {
            sendErrorResponse(exchange, 404, "Address not within any memory block", "MEMORY_ACCESS_ERROR");
            return;
        }

        ColorizingService colorService = getColorizingService();
        if (colorService == null) {
            sendErrorResponse(exchange, 503, "Colorizing service not available", "SERVICE_UNAVAILABLE");
            return;
        }

        if ("POST".equals(method) || "PATCH".equals(method)) {
            Map<String, String> payload = parseJsonPostParams(exchange);
            String colorValue = payload.get("color");
            if (colorValue == null || colorValue.trim().isEmpty()) {
                sendErrorResponse(exchange, 400, "'color' field required", "MISSING_PARAMETER");
                return;
            }

            Color color = parseColorValue(colorValue);
            if (color == null) {
                sendErrorResponse(exchange, 400,
                    "Invalid color. Use #RRGGBB, #AARRGGBB, RRGGBB, AARRGGBB, or java.awt.Color name",
                    "INVALID_PARAMETER");
                return;
            }

            try {
                final Address targetAddress = address;
                final Color targetColor = color;
                final ColorizingService targetService = colorService;
                boolean success = executeOnEdtInTransaction(program,
                    "Set background color at " + targetAddress,
                    () -> setBackgroundColor(targetService, targetAddress, targetColor));

                if (!success) {
                    sendErrorResponse(exchange, 500, "Failed to set background color", "BACKGROUND_COLOR_SET_FAILED");
                    return;
                }
            } catch (Exception e) {
                sendErrorResponse(exchange, 500, "Failed to set background color: " + e.getMessage(),
                    "BACKGROUND_COLOR_SET_FAILED");
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("address", address.toString());
            result.put("color", String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));
            result.put("alpha", color.getAlpha());

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result)
                .addLink("self", "/memory/" + addressStr + "/background-color")
                .addLink("clearAll", "/memory/background-colors");

            sendJsonResponse(exchange, builder.build(), 200);
            return;
        }

        if ("DELETE".equals(method)) {
            try {
                final Address targetAddress = address;
                final ColorizingService targetService = colorService;
                boolean success = executeOnEdtInTransaction(program,
                    "Clear background color at " + targetAddress,
                    () -> clearBackgroundColorAtAddress(targetService, targetAddress));

                if (!success) {
                    sendErrorResponse(exchange, 500, "Failed to clear background color", "BACKGROUND_COLOR_CLEAR_FAILED");
                    return;
                }
            } catch (Exception e) {
                sendErrorResponse(exchange, 500, "Failed to clear background color: " + e.getMessage(),
                    "BACKGROUND_COLOR_CLEAR_FAILED");
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("address", address.toString());
            result.put("cleared", true);

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result)
                .addLink("self", "/memory/" + addressStr + "/background-color")
                .addLink("clearAll", "/memory/background-colors");

            sendJsonResponse(exchange, builder.build(), 200);
            return;
        }

        sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
    } catch (Exception e) {
        Msg.error(this, "Error handling memory background color endpoint", e);
        sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage(), "INTERNAL_ERROR");
    }
}

/**
 * Handle setting background colors for multiple addresses and removing all background colors.
 * Endpoint: /memory/background-colors
 */
private void handleBackgroundColorsRequest(HttpExchange exchange) throws IOException {
    try {
        String method = exchange.getRequestMethod();
        if (!("POST".equals(method) || "PATCH".equals(method) || "DELETE".equals(method))) {
            sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
            return;
        }

        Program program = getCurrentProgram();
        if (program == null) {
            sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
            return;
        }

        ColorizingService colorService = getColorizingService();
        if (colorService == null) {
            sendErrorResponse(exchange, 503, "Colorizing service not available", "SERVICE_UNAVAILABLE");
            return;
        }

        if ("POST".equals(method) || "PATCH".equals(method)) {
            Map<String, String> payload = parseJsonPostParams(exchange);
            String colorValue = payload.get("color");
            String addressesRaw = payload.get("addresses");

            if (colorValue == null || colorValue.trim().isEmpty()) {
                sendErrorResponse(exchange, 400, "'color' field required", "MISSING_PARAMETER");
                return;
            }
            if (addressesRaw == null || addressesRaw.trim().isEmpty()) {
                sendErrorResponse(exchange, 400, "'addresses' field required", "MISSING_PARAMETER");
                return;
            }

            Color color = parseColorValue(colorValue);
            if (color == null) {
                sendErrorResponse(exchange, 400,
                    "Invalid color. Use #RRGGBB, #AARRGGBB, RRGGBB, AARRGGBB, or java.awt.Color name",
                    "INVALID_PARAMETER");
                return;
            }

            List<String> addressInputs;
            try {
                addressInputs = parseAddressInputs(addressesRaw);
            } catch (IllegalArgumentException ex) {
                sendErrorResponse(exchange, 400, ex.getMessage(), "INVALID_PARAMETER");
                return;
            }

            if (addressInputs.isEmpty()) {
                sendErrorResponse(exchange, 400, "'addresses' must contain at least one address", "INVALID_PARAMETER");
                return;
            }

            List<String> normalizedAddresses = new ArrayList<>();
            AddressSet addressSet = new AddressSet();
            for (String input : addressInputs) {
                Address parsed;
                try {
                    parsed = program.getAddressFactory().getAddress(input);
                } catch (Exception e) {
                    sendErrorResponse(exchange, 400, "Invalid address format: " + input, "INVALID_ADDRESS");
                    return;
                }

                if (!program.getMemory().contains(parsed)) {
                    sendErrorResponse(exchange, 404, "Address not within any memory block: " + parsed, "MEMORY_ACCESS_ERROR");
                    return;
                }

                normalizedAddresses.add(parsed.toString());
                addressSet.add(parsed);
            }

            try {
                final ColorizingService typedService = colorService;
                final Color targetColor = color;
                final AddressSet targetSet = addressSet;
                boolean success = executeOnEdtInTransaction(program,
                    "Set background colors",
                    () -> setBackgroundColor(typedService, targetSet, targetColor));
                if (!success) {
                    sendErrorResponse(exchange, 500, "Failed to set background colors", "BACKGROUND_COLOR_SET_FAILED");
                    return;
                }
            } catch (Exception e) {
                sendErrorResponse(exchange, 500, "Failed to set background colors: " + e.getMessage(),
                    "BACKGROUND_COLOR_SET_FAILED");
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("addresses", normalizedAddresses);
            result.put("count", normalizedAddresses.size());
            result.put("color", String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue()));
            result.put("alpha", color.getAlpha());

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result)
                .addLink("self", "/memory/background-colors");

            sendJsonResponse(exchange, builder.build(), 200);
            return;
        }

        try {
            final ColorizingService targetService = colorService;
            boolean success = executeOnEdtInTransaction(program,
                "Clear all background colors",
                () -> clearAllBackgroundColors(targetService, program));

            if (!success) {
                sendErrorResponse(exchange, 500, "Failed to clear all background colors", "BACKGROUND_COLOR_CLEAR_FAILED");
                return;
            }
        } catch (Exception e) {
            sendErrorResponse(exchange, 500, "Failed to clear all background colors: " + e.getMessage(),
                "BACKGROUND_COLOR_CLEAR_FAILED");
            return;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("cleared", true);
        result.put("scope", "all");

        ResponseBuilder builder = new ResponseBuilder(exchange, port)
            .success(true)
            .result(result)
            .addLink("self", "/memory/background-colors");

        sendJsonResponse(exchange, builder.build(), 200);
    } catch (Exception e) {
        Msg.error(this, "Error handling clear all background colors endpoint", e);
        sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage(), "INTERNAL_ERROR");
    }
}

private ColorizingService getColorizingService() {
    PluginTool currentTool = getTool();
    if (currentTool == null) {
        return null;
    }

    try {
        return currentTool.getService(ColorizingService.class);
    } catch (Exception e) {
        Msg.debug(this, "Error resolving ColorizingService: " + e.getMessage());
        return null;
    }
}

private boolean setBackgroundColor(ColorizingService colorService, Address address, Color color) {
    AddressSet set = new AddressSet(address, address);
    return setBackgroundColor(colorService, set, color);
}

private boolean setBackgroundColor(ColorizingService colorService, AddressSetView set, Color color) {
    if (set == null || set.isEmpty()) {
        throw new IllegalArgumentException("Address set is empty");
    }
    colorService.setBackgroundColor(set, color);
    return true;
}

private boolean clearBackgroundColorAtAddress(ColorizingService colorService, Address address) {
    colorService.clearBackgroundColor(address, address);
    return true;
}

private boolean clearAllBackgroundColors(ColorizingService colorService, Program program) {
    colorService.clearAllBackgroundColors();
    return true;
}

@FunctionalInterface
private interface EdtSupplier<T> {
    T get() throws Exception;
}

private <T> T executeOnEdt(EdtSupplier<T> operation) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
        return operation.get();
    }

    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Exception> error = new AtomicReference<>();

    SwingUtilities.invokeAndWait(() -> {
        try {
            result.set(operation.get());
        } catch (Exception e) {
            error.set(e);
        }
    });

    if (error.get() != null) {
        throw error.get();
    }

    return result.get();
}

private <T> T executeOnEdtInTransaction(Program program, String transactionName, EdtSupplier<T> operation)
        throws Exception {
    return executeOnEdt(() -> {
        int txId = program.startTransaction(transactionName);
        if (txId < 0) {
            throw new IllegalStateException("Failed to start transaction: " + transactionName);
        }

        boolean commit = false;
        try {
            T result = operation.get();
            commit = true;
            return result;
        } finally {
            program.endTransaction(txId, commit);
        }
    });
}

private List<String> parseAddressInputs(String addressesRaw) {
    try {
        JsonElement parsed = gson.fromJson(addressesRaw, JsonElement.class);
        List<String> out = new ArrayList<>();

        if (parsed == null || parsed.isJsonNull()) {
            return out;
        }

        if (parsed.isJsonArray()) {
            JsonArray arr = parsed.getAsJsonArray();
            for (JsonElement el : arr) {
                if (el == null || el.isJsonNull()) {
                    continue;
                }
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    out.add(el.getAsString().trim());
                } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                    out.add("0x" + Long.toHexString(el.getAsLong()));
                } else {
                    throw new IllegalArgumentException("'addresses' must contain only string or numeric addresses");
                }
            }
            return out;
        }

        // Fallback: single value provided
        if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()) {
            out.add(parsed.getAsString().trim());
            return out;
        }
        if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isNumber()) {
            out.add("0x" + Long.toHexString(parsed.getAsLong()));
            return out;
        }

        throw new IllegalArgumentException("'addresses' must be an array of addresses");
    } catch (IllegalArgumentException e) {
        throw e;
    } catch (Exception e) {
        throw new IllegalArgumentException("Invalid 'addresses' payload: expected JSON array");
    }
}

private Color parseColorValue(String colorValue) {
    String value = colorValue.trim();

    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("java.awt.color.")) {
        value = value.substring("java.awt.Color.".length());
    }
    else if (lower.startsWith("color.")) {
        value = value.substring("Color.".length());
    }

    // Named java.awt.Color constants (e.g. red, BLUE)
    try {
        Field field = Color.class.getField(value.toUpperCase(Locale.ROOT));
        if (Color.class.isAssignableFrom(field.getType())) {
            return (Color) field.get(null);
        }
    } catch (Exception ignored) {
        // Continue with hex parsing
    }

    if (value.startsWith("#")) {
        value = value.substring(1);
    }

    // RRGGBB
    if (value.matches("(?i)^[0-9a-f]{6}$")) {
        int rgb = Integer.parseInt(value, 16);
        return new Color(rgb);
    }

    // AARRGGBB
    if (value.matches("(?i)^[0-9a-f]{8}$")) {
        long argb = Long.parseLong(value, 16);
        int a = (int) ((argb >> 24) & 0xFF);
        int r = (int) ((argb >> 16) & 0xFF);
        int g = (int) ((argb >> 8) & 0xFF);
        int b = (int) (argb & 0xFF);
        return new Color(r, g, b, a);
    }

    return null;
}

/**
 * Handle requests to set or get comments at a specific memory address
 */
private void handleMemoryComments(HttpExchange exchange, String addressStr, String commentType) throws IOException {
    try {
        String method = exchange.getRequestMethod();
        Program program = getCurrentProgram();

        if (program == null) {
            sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
            return;
        }

        // Parse address
        AddressFactory addressFactory = program.getAddressFactory();
        Address address;
        try {
            address = addressFactory.getAddress(addressStr);
        } catch (Exception e) {
            sendErrorResponse(exchange, 400, "Invalid address format: " + addressStr, "INVALID_ADDRESS");
            return;
        }

        // Validate comment type
        if (!isValidCommentType(commentType)) {
            sendErrorResponse(exchange, 400, "Invalid comment type: " + commentType, "INVALID_COMMENT_TYPE");
            return;
        }

        if ("GET".equals(method)) {
            // Get existing comment
            String comment = getCommentByType(program, address, commentType);

            Map<String, Object> result = new HashMap<>();
            result.put("address", addressStr);
            result.put("comment_type", commentType);
            result.put("comment", comment != null ? comment : "");

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result)
                .addLink("self", "/memory/" + addressStr + "/comments/" + commentType);

            sendJsonResponse(exchange, builder.build(), 200);

        } else if ("POST".equals(method)) {
            // Set comment
            Map<String, String> params = parseJsonPostParams(exchange);
            String comment = params.get("comment");

            if (comment == null) {
                sendErrorResponse(exchange, 400, "Comment parameter is required", "MISSING_PARAMETER");
                return;
            }

            boolean success = setCommentByType(program, address, commentType, comment);

            if (success) {
                Map<String, Object> result = new HashMap<>();
                result.put("address", addressStr);
                result.put("comment_type", commentType);
                result.put("comment", comment);

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true)
                    .result(result)
                    .addLink("self", "/memory/" + addressStr + "/comments/" + commentType);

                sendJsonResponse(exchange, builder.build(), 200);
            } else {
                sendErrorResponse(exchange, 500, "Failed to set comment", "COMMENT_SET_FAILED");
            }
        } else {
            sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
        }
    } catch (Exception e) {
        Msg.error(this, "Error handling memory comments", e);
        sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage(), "INTERNAL_ERROR");
    }
}

/**
 * Check if the comment type is valid
 */
private boolean isValidCommentType(String commentType) {
    return commentType.equals("plate") ||
           commentType.equals("pre") ||
           commentType.equals("post") ||
           commentType.equals("eol") ||
           commentType.equals("repeatable");
}

/**
 * Get a comment by type at the specified address
 */
private String getCommentByType(Program program, Address address, String commentType) {
    if (program == null) return null;

    CommentType type = getCommentType(commentType);
    return program.getListing().getComment(type, address);
}

/**
 * Set a comment by type at the specified address
 */
private boolean setCommentByType(Program program, Address address, String commentType, String comment) {
    if (program == null) return false;

    CommentType type = getCommentType(commentType);

    try {
        return TransactionHelper.executeInTransaction(program, "Set " + commentType + " comment at " + address, () -> {
            program.getListing().setComment(address, type, comment);
            return true;
        });
    } catch (Exception e) {
        Msg.error(this, "Error setting comment", e);
        return false;
    }
}

/**
 * Convert comment type string to Ghidra's CommentType enum
 */
private CommentType getCommentType(String commentType) {
    switch (commentType.toLowerCase()) {
        case "plate":
            return CommentType.PLATE;
        case "pre":
            return CommentType.PRE;
        case "post":
            return CommentType.POST;
        case "eol":
            return CommentType.EOL;
        case "repeatable":
            return CommentType.REPEATABLE;
        default:
            return CommentType.PLATE;
    }
}

private void handleDisassemblyAtAddress(HttpExchange exchange, String addressStr) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            Program program = getCurrentProgram();
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseQueryParams(exchange);
            String limitStr = params.get("limit") != null ? params.get("limit") : params.get("count");
            int count = parseIntOrDefault(limitStr, 50);
            int offset = parseIntOrDefault(params.get("offset"), 0);

            AddressFactory addressFactory = program.getAddressFactory();
            Address startAddr;
            try {
                startAddr = addressFactory.getAddress(addressStr);
            } catch (Exception e) {
                sendErrorResponse(exchange, 400, "Invalid address format: " + addressStr, "INVALID_ADDRESS");
                return;
            }

            if (startAddr == null) {
                sendErrorResponse(exchange, 400, "Invalid address: " + addressStr, "INVALID_ADDRESS");
                return;
            }

            ghidra.program.model.listing.Listing listing = program.getListing();
            Memory mem = program.getMemory();
            ghidra.program.model.listing.InstructionIterator instrIter =
                listing.getInstructions(startAddr, true);

            List<Map<String, Object>> allInstructions = new ArrayList<>();
            int totalScanned = 0;

            while (instrIter.hasNext() && totalScanned < offset + count) {
                ghidra.program.model.listing.Instruction instr = instrIter.next();
                totalScanned++;

                if (totalScanned <= offset) {
                    continue;
                }

                Map<String, Object> instrMap = new HashMap<>();
                instrMap.put("address", instr.getAddress().toString());

                try {
                    byte[] bytes = new byte[instr.getLength()];
                    mem.getBytes(instr.getAddress(), bytes);
                    StringBuilder hexBytes = new StringBuilder();
                    for (byte b : bytes) {
                        hexBytes.append(String.format("%02X", b & 0xFF));
                    }
                    instrMap.put("bytes", hexBytes.toString());
                } catch (MemoryAccessException e) {
                    instrMap.put("bytes", "??");
                }

                instrMap.put("mnemonic", instr.getMnemonicString());
                instrMap.put("operands", instr.toString().substring(instr.getMnemonicString().length()).trim());
                allInstructions.add(instrMap);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("startAddress", addressStr);
            result.put("instructions", allInstructions);
            result.put("totalInstructions", allInstructions.size());

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result)
                .addLink("self", "/memory/" + addressStr + "/disassembly?count=" + count);

            if (!allInstructions.isEmpty()) {
                String lastAddr = (String) allInstructions.get(allInstructions.size() - 1).get("address");
                builder.addLink("next", "/memory/" + lastAddr + "/disassembly?count=" + count);
            }

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in disassembly at address endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private void handleMemoryBlocksRequest(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                Map<String, String> qparams = parseQueryParams(exchange);
                int offset = parseIntOrDefault(qparams.get("offset"), 0);
                int limit = parseIntOrDefault(qparams.get("limit"), 100);

                Program program = getCurrentProgram();
                if (program == null) {
                    sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                    return;
                }

                // Create ResponseBuilder for HATEOAS-compliant response
                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true)
                    .addLink("self", "/memory/blocks" + (exchange.getRequestURI().getRawQuery() != null ?
                        "?" + exchange.getRequestURI().getRawQuery() : ""));

                // Add common links
                builder.addLink("program", "/program");
                builder.addLink("memory", "/memory");

                // Get memory blocks
                Memory memory = program.getMemory();
                List<Map<String, Object>> blocks = new ArrayList<>();

                for (MemoryBlock block : memory.getBlocks()) {
                    Map<String, Object> blockInfo = new HashMap<>();
                    blockInfo.put("name", block.getName());
                    blockInfo.put("start", block.getStart().toString());
                    blockInfo.put("end", block.getEnd().toString());
                    blockInfo.put("size", block.getSize());
                    blockInfo.put("permissions", getPermissionString(block));
                    blockInfo.put("isInitialized", block.isInitialized());
                    blockInfo.put("isLoaded", block.isLoaded());
                    blockInfo.put("isMapped", block.isMapped());
                    blocks.add(blockInfo);
                }

                // Apply pagination and add it to result
                List<Map<String, Object>> paginatedBlocks =
                    applyPagination(blocks, offset, limit, builder, "/memory/blocks");

                // Add the result to the builder
                builder.result(paginatedBlocks);

                // Send the HATEOAS-compliant response
                sendJsonResponse(exchange, builder.build(), 200);

            } else if ("POST".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                boolean mapAliasPath = "/memory/map".equals(path) || "/memory/map/".equals(path);
                boolean ensurePath = "/memory/blocks/ensure".equals(path) || "/memory/blocks/ensure/".equals(path);
                if (!mapAliasPath && !ensurePath && !"/memory/blocks".equals(path) && !"/memory/blocks/".equals(path)) {
                    sendErrorResponse(exchange, 404, "Memory block resource not found: " + path, "RESOURCE_NOT_FOUND");
                    return;
                }

                Program program = getCurrentProgram();
                if (program == null) {
                    sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                    return;
                }

                Map<String, String> payload = parseJsonPostParams(exchange);
                MemoryBlockRequest request = parseMemoryBlockRequest(exchange, program, payload);
                if (request == null) {
                    return;
                }

                Memory memory = program.getMemory();
                boolean ensureMode = ensurePath || MEMORY_BLOCK_MODE_CREATE_OR_UPDATE.equalsIgnoreCase(request.mode);

                MemoryBlock resultBlock;
                String action;
                try {
                    if (ensureMode) {
                        EnsureBlockResult ensureResult = ensureMemoryBlock(program, memory, request);
                        resultBlock = ensureResult.block;
                        action = ensureResult.action;
                    } else {
                        MemoryBlock existingBlock = memory.getBlock(request.name);
                        if (existingBlock != null) {
                            Map<String, Object> details = new LinkedHashMap<>();
                            details.put("error_code", "E_BLOCK_EXISTS");
                            details.put("conflicting_block_name", existingBlock.getName());
                            details.put("conflicting_range_start", existingBlock.getStart().toString());
                            details.put("conflicting_range_end", existingBlock.getEnd().toString());
                            details.put("required_action", "use ensure_memory_block or choose a different name");
                            sendDetailedErrorResponse(exchange, 409,
                                "Memory block already exists: " + request.name,
                                "ALREADY_EXISTS",
                                details);
                            return;
                        }

                        MemoryBlock conflictingBlock = findIntersectingBlock(memory, request.start, request.end, null);
                        if (conflictingBlock != null) {
                            sendBlockOverlapError(exchange, conflictingBlock, request.start, request.end);
                            return;
                        }

                        resultBlock = TransactionHelper.executeInTransaction(program, "Create Memory Block", () ->
                            createMemoryBlock(memory, request)
                        );
                        action = "created";
                    }
                } catch (TransactionHelper.TransactionException tex) {
                    sendMemoryBlockMutationError(exchange, tex);
                    return;
                } catch (Exception e) {
                    sendMemoryBlockMutationError(exchange, e);
                    return;
                }

                if (resultBlock == null) {
                    sendDetailedErrorResponse(exchange, 500,
                        "Memory block creation returned no block",
                        "MEMORY_BLOCK_CREATE_FAILED",
                        Map.of(
                            "error_code", "E_INTERNAL_ERROR",
                            "required_action", "retry"
                        ));
                    return;
                }

                Map<String, Object> result = buildMemoryBlockInfo(resultBlock);
                result.put("action", action);
                result.put("created", "created".equals(action));
                result.put("updated", "updated".equals(action));
                result.put("unchanged", "unchanged".equals(action));

                String selfPath = mapAliasPath ? "/memory/map" :
                    (ensureMode ? "/memory/blocks/ensure" : "/memory/blocks");

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true)
                    .result(result)
                    .addLink("self", selfPath)
                    .addLink("memory", "/memory")
                    .addLink("segments", "/segments");

                int statusCode = "created".equals(action) ? 201 : 200;
                sendJsonResponse(exchange, builder.build(), statusCode);

            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                if (!path.startsWith("/memory/blocks/")) {
                    sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                    return;
                }

                String identifier = path.substring("/memory/blocks/".length());
                if (identifier.endsWith("/")) {
                    identifier = identifier.substring(0, identifier.length() - 1);
                }
                if (identifier.isBlank()) {
                    sendErrorResponse(exchange, 400, "Block identifier is required", "MISSING_PARAMETER");
                    return;
                }

                Program program = getCurrentProgram();
                if (program == null) {
                    sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                    return;
                }

                Memory memory = program.getMemory();
                MemoryBlock targetBlock = null;

                try {
                    Address byAddress = program.getAddressFactory().getAddress(identifier);
                    if (byAddress != null) {
                        targetBlock = memory.getBlock(byAddress);
                    }
                } catch (Exception ignored) {
                    // Fall back to resolving by block name.
                }

                if (targetBlock == null) {
                    targetBlock = memory.getBlock(identifier);
                }

                if (targetBlock == null) {
                    sendErrorResponse(exchange, 404, "Memory block not found: " + identifier, "MEMORY_BLOCK_NOT_FOUND");
                    return;
                }

                final String deletedName = targetBlock.getName();
                final String deletedStart = targetBlock.getStart().toString();
                final String deletedEnd = targetBlock.getEnd().toString();
                final long deletedSize = targetBlock.getSize();

                try {
                    final MemoryBlock blockToDelete = targetBlock;
                    TransactionHelper.executeInTransaction(program, "Delete Memory Block", () -> {
                        removeBlock(memory, blockToDelete);
                        return true;
                    });
                } catch (Exception e) {
                    sendMemoryBlockMutationError(exchange, e instanceof Exception ? (Exception) e : new Exception(e));
                    return;
                }

                Map<String, Object> result = new HashMap<>();
                result.put("deleted", true);
                result.put("name", deletedName);
                result.put("start", deletedStart);
                result.put("end", deletedEnd);
                result.put("size", deletedSize);

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true)
                    .result(result)
                    .addLink("self", "/memory/blocks/" + identifier)
                    .addLink("blocks", "/memory/blocks")
                    .addLink("memory", "/memory");

                sendJsonResponse(exchange, builder.build(), 200);

            } else {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            Msg.error(this, "Error in /memory/blocks endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleMemoryWriteAlias(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"POST".equals(method) && !"PATCH".equals(method) && !"PUT".equals(method)) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Map<String, String> payload = parseJsonPostParams(exchange);
            String addressStr = Optional.ofNullable(payload.get("address")).orElse(payload.get("start"));
            if (addressStr == null || addressStr.isBlank()) {
                sendErrorResponse(exchange, 400, "'address' field required", "MISSING_PARAMETER");
                return;
            }

            writeMemory(exchange, addressStr, payload, "/memory/write");
        } catch (Exception e) {
            Msg.error(this, "Error in /memory/write endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    private void writeMemory(HttpExchange exchange,
                             String addressStr,
                             Map<String, String> payload,
                             String selfPath) throws IOException {
        Program program = getCurrentProgram();
        if (program == null) {
            sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
            return;
        }

        Address address;
        try {
            address = program.getAddressFactory().getAddress(addressStr);
        } catch (Exception e) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("address", addressStr);
            details.put("required_action", "use a valid program address");
            sendDetailedErrorResponse(exchange, 400,
                "Invalid address format: " + addressStr,
                "INVALID_ADDRESS",
                details);
            return;
        }

        String bytesStr = Optional.ofNullable(payload.get("bytes")).orElse(payload.get("bytes_data"));
        String inputFormat = payload.getOrDefault("format", "hex").toLowerCase(Locale.ROOT);
        boolean force = Boolean.parseBoolean(Optional.ofNullable(payload.get("force")).orElse("false"));

        if (bytesStr == null || bytesStr.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("required_parameter", "bytes or bytes_data");
            sendDetailedErrorResponse(exchange, 400,
                "Missing bytes parameter",
                "MISSING_PARAMETER",
                details);
            return;
        }

        if (!inputFormat.equals("hex") && !inputFormat.equals("base64") && !inputFormat.equals("string")) {
            sendErrorResponse(exchange, 400, "Invalid format parameter (must be 'hex', 'base64', or 'string')", "INVALID_PARAMETER");
            return;
        }

        byte[] bytes;
        try {
            bytes = decodeBytes(bytesStr, inputFormat);
        } catch (Exception e) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("format", inputFormat);
            details.put("required_action", "ensure payload encoding matches 'format'");
            details.put("cause", Optional.ofNullable(e.getMessage()).orElse("unknown"));
            sendDetailedErrorResponse(exchange, 400,
                "Invalid bytes format: " + e.getMessage(),
                "INVALID_PARAMETER",
                details);
            return;
        }

        if (bytes.length == 0) {
            sendErrorResponse(exchange, 400, "Decoded byte array empty", "INVALID_PARAMETER");
            return;
        }
        if (bytes.length > MAX_MEMORY_LENGTH) {
            sendErrorResponse(exchange, 400, "Write length exceeds max of " + MAX_MEMORY_LENGTH, "LIMIT_EXCEEDED");
            return;
        }

        Memory memory = program.getMemory();
        if (!memory.contains(address)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("address", address.toString());
            details.put("required_action", "create/ensure a memory block at this address first");
            sendDetailedErrorResponse(exchange, 404,
                "Address not within any memory block",
                "MEMORY_ACCESS_ERROR",
                details);
            return;
        }

        MemoryBlock block = memory.getBlock(address);
        if (block == null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("address", address.toString());
            details.put("required_action", "create/ensure a memory block first");
            sendDetailedErrorResponse(exchange, 404,
                "No memory block for address",
                "MEMORY_BLOCK_NOT_FOUND",
                details);
            return;
        }
        // For a loader-like API, allow writes to non-writable blocks by
        // temporarily making them writable, performing the write, and restoring permissions.
        // This is necessary because we may be mapping segments with no write permissions
        // but still need to write data like a loader would do.
        final boolean wasNotWritable = !block.isWrite();
        final boolean originalWrite = block.isWrite();
        final boolean originalRead = block.isRead();
        final boolean originalExecute = block.isExecute();

        // Uninitialized blocks have no byte storage — setBytes() will throw.
        // Auto-convert to an initialized block filled with 0x00 so the write can proceed.
        // Use reflection to call memory.convertToInitialized() so the compiler does not need
        // ghidra.framework.store.LockException on the classpath (it is not bundled in lib/).
        if (!block.isInitialized()) {
            final MemoryBlock blockRef = block;
            try {
                TransactionHelper.executeInTransaction(program,
                    "Initialize Memory Block: " + block.getName(),
                    () -> {
                        try {
                            Method m = memory.getClass().getMethod("convertToInitialized",
                                MemoryBlock.class, byte.class);
                            m.invoke(memory, blockRef, (byte) 0x00);
                        } catch (InvocationTargetException ite) {
                            Throwable cause = ite.getCause();
                            throw new Exception("convertToInitialized failed: " +
                                (cause != null ? cause.getMessage() : ite.getMessage()), cause != null ? cause : ite);
                        }
                        return true;
                    });
                // Re-fetch after conversion (block object may be stale).
                block = memory.getBlock(address);
                if (block == null) {
                    sendErrorResponse(exchange, 500,
                        "Memory block lost after initialization", "MEMORY_WRITE_FAILED");
                    return;
                }
            } catch (Exception e) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("block_name", blockRef.getName());
                details.put("cause", Optional.ofNullable(e.getMessage()).orElse("unknown"));
                details.put("required_action", "recreate block with initialized=true");
                sendDetailedErrorResponse(exchange, 500,
                    "Failed to initialize memory block: " + e.getMessage(),
                    "BLOCK_INIT_FAILED",
                    details);
                return;
            }
        }

        long remaining = block.getEnd().getOffset() - address.getOffset() + 1;
        if (bytes.length > remaining) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("block_name", block.getName());
            details.put("block_start", block.getStart().toString());
            details.put("block_end", block.getEnd().toString());
            details.put("block_size", block.getSize());
            details.put("address", address.toString());
            details.put("requested_bytes", bytes.length);
            details.put("remaining_bytes", remaining);
            details.put("required_action", "ensure block size/range matches the region before writing");
            sendDetailedErrorResponse(exchange, 400,
                "Write exceeds block boundary (remaining=" + remaining + ")",
                "WRITE_OUT_OF_RANGE",
                details);
            return;
        }

        boolean autoClearedCodeUnits = false;
        boolean autoMadeWritable = false;
        try {
            performWriteInTransaction(program, memory, address, bytes, force, block, wasNotWritable);
            autoMadeWritable = wasNotWritable;
        } catch (Exception e) {
            if (!force) {
                try {
                    // Auto-retry with cleared code units to handle listing/code-unit conflicts.
                    performWriteInTransaction(program, memory, address, bytes, true, block, wasNotWritable);
                    autoClearedCodeUnits = true;
                    autoMadeWritable = wasNotWritable;
                } catch (Exception retryError) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("address", address.toString());
                    details.put("block_name", block.getName());
                    details.put("bytes", bytes.length);
                    details.put("format", inputFormat);
                    details.put("cause", Optional.ofNullable(retryError.getMessage()).orElse("unknown"));
                    details.put("required_action", "retry with force=true and verify block range/permissions");
                    sendDetailedErrorResponse(exchange, 500,
                        "Memory write transaction error: " + retryError.getMessage(),
                        "MEMORY_WRITE_FAILED",
                        details);
                    return;
                }
            } else {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("address", address.toString());
                details.put("block_name", block.getName());
                details.put("bytes", bytes.length);
                details.put("format", inputFormat);
                details.put("cause", Optional.ofNullable(e.getMessage()).orElse("unknown"));
                details.put("required_action", "verify block range/permissions and active transactions in Ghidra");
                sendDetailedErrorResponse(exchange, 500,
                    "Memory write transaction error: " + e.getMessage(),
                    "MEMORY_WRITE_FAILED",
                    details);
                return;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("address", addressStr);
        result.put("length", bytes.length);
        result.put("bytesWritten", bytes.length);
        result.put("format", inputFormat);
        result.put("force", force || autoClearedCodeUnits);
        result.put("autoClearedCodeUnits", autoClearedCodeUnits);
        result.put("autoMadeWritable", autoMadeWritable);
        result.put("hexBytes", toHex(bytes));
        result.put("rawBytes", Base64.getEncoder().encodeToString(bytes));

        ResponseBuilder builder = new ResponseBuilder(exchange, port)
            .success(true)
            .result(result)
            .addLink("self", selfPath)
            .addLink("memory", "/memory/" + addressStr);

        sendJsonResponse(exchange, builder.build(), 200);
    }

    private void performWriteInTransaction(Program program,
                                           Memory memory,
                                           Address address,
                                           byte[] bytes,
                                           boolean clearCodeUnits,
                                           MemoryBlock block,
                                           boolean wasNotWritable) throws Exception {
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
    }

    private MemoryBlockRequest parseMemoryBlockRequest(HttpExchange exchange,
                                                       Program program,
                                                       Map<String, String> payload) throws IOException {
        String name = payload.get("name");
        String addressStr = Optional.ofNullable(payload.get("address")).orElse(payload.get("start"));
        String sizeStr = payload.get("size");
        String mode = Optional.ofNullable(payload.get("mode")).orElse(MEMORY_BLOCK_MODE_CREATE);

        if (name == null || name.isBlank()) {
            sendErrorResponse(exchange, 400, "'name' field required", "MISSING_PARAMETER");
            return null;
        }
        if (addressStr == null || addressStr.isBlank()) {
            sendErrorResponse(exchange, 400, "'address' field required", "MISSING_PARAMETER");
            return null;
        }
        if (sizeStr == null || sizeStr.isBlank()) {
            sendErrorResponse(exchange, 400, "'size' field required", "MISSING_PARAMETER");
            return null;
        }
        if (!MEMORY_BLOCK_MODE_CREATE.equalsIgnoreCase(mode) &&
            !MEMORY_BLOCK_MODE_CREATE_OR_UPDATE.equalsIgnoreCase(mode)) {
            sendErrorResponse(exchange, 400,
                "'mode' must be one of: create, create_or_update",
                "INVALID_PARAMETER");
            return null;
        }

        long size;
        try {
            size = Long.parseLong(sizeStr);
        } catch (NumberFormatException nfe) {
            sendErrorResponse(exchange, 400, "'size' must be a positive integer", "INVALID_PARAMETER");
            return null;
        }
        if (size <= 0) {
            sendErrorResponse(exchange, 400, "'size' must be greater than 0", "INVALID_PARAMETER");
            return null;
        }

        AddressFactory addressFactory = program.getAddressFactory();
        Address start;
        try {
            start = addressFactory.getAddress(addressStr);
        } catch (Exception e) {
            sendErrorResponse(exchange, 400, "Invalid address format: " + addressStr, "INVALID_PARAMETER");
            return null;
        }
        if (start == null) {
            sendErrorResponse(exchange, 400, "Invalid address format: " + addressStr, "INVALID_PARAMETER");
            return null;
        }

        Address end;
        try {
            end = start.addNoWrap(size - 1);
        } catch (AddressOverflowException aoe) {
            sendErrorResponse(exchange, 400, "Address range overflows address space", "INVALID_PARAMETER");
            return null;
        }

        boolean readable = Boolean.parseBoolean(Optional.ofNullable(payload.get("readable")).orElse("true"));
        boolean writable = Boolean.parseBoolean(Optional.ofNullable(payload.get("writable")).orElse("false"));
        boolean executable = Boolean.parseBoolean(Optional.ofNullable(payload.get("executable")).orElse("false"));
        boolean initialized = Boolean.parseBoolean(Optional.ofNullable(payload.get("initialized")).orElse("false"));

        return new MemoryBlockRequest(
            name,
            start,
            size,
            end,
            readable,
            writable,
            executable,
            initialized,
            mode
        );
    }

    static final class EnsureBlockResult {
        private final MemoryBlock block;
        private final String action;

        private EnsureBlockResult(MemoryBlock block, String action) {
            this.block = block;
            this.action = action;
        }

        MemoryBlock getBlock() {
            return block;
        }

        String getAction() {
            return action;
        }
    }

    private EnsureBlockResult ensureMemoryBlock(Program program,
                                                Memory memory,
                                                MemoryBlockRequest request) throws Exception {
        return TransactionHelper.executeInTransaction(program, "Ensure Memory Block", () ->
            ensureMemoryBlockInTransaction(memory, request)
        );
    }

    EnsureBlockResult ensureMemoryBlockInTransaction(Memory memory,
                                                     String name,
                                                     Address start,
                                                     long size,
                                                     boolean readable,
                                                     boolean writable,
                                                     boolean executable,
                                                     boolean initialized) throws Exception {
        Address end = start.addNoWrap(size - 1);
        MemoryBlockRequest request = new MemoryBlockRequest(
            name,
            start,
            size,
            end,
            readable,
            writable,
            executable,
            initialized,
            MEMORY_BLOCK_MODE_CREATE_OR_UPDATE
        );
        return ensureMemoryBlockInTransaction(memory, request);
    }

    private EnsureBlockResult ensureMemoryBlockInTransaction(Memory memory,
                                                             MemoryBlockRequest request) throws Exception {
        MemoryBlock existingBlock = memory.getBlock(request.name);
        if (existingBlock == null) {
            MemoryBlock conflictingBlock = findIntersectingBlock(memory, request.start, request.end, null);
            if (conflictingBlock != null) {
                throw new MemoryConflictException("Requested range overlaps existing memory block: " + conflictingBlock.getName());
            }

            MemoryBlock created = createMemoryBlock(memory, request);
            return new EnsureBlockResult(created, "created");
        }

        MemoryBlock conflictingBlock = findIntersectingBlock(memory, request.start, request.end, existingBlock);
        if (conflictingBlock != null) {
            throw new MemoryConflictException("Requested range overlaps existing memory block: " + conflictingBlock.getName());
        }

        boolean sameRange = existingBlock.getStart().equals(request.start) && existingBlock.getSize() == request.size;
        boolean samePermissions = existingBlock.isRead() == request.readable &&
            existingBlock.isWrite() == request.writable &&
            existingBlock.isExecute() == request.executable;
        boolean sameInitialization = existingBlock.isInitialized() == request.initialized;

        if (sameRange && samePermissions && sameInitialization) {
            return new EnsureBlockResult(existingBlock, "unchanged");
        }

        MemoryBlock updated = existingBlock;
        if (!sameRange || !sameInitialization) {
            removeBlock(memory, existingBlock);
            updated = createMemoryBlock(memory, request);
        } else {
            applyBlockPermissions(updated, request.readable, request.writable, request.executable);
        }

        return new EnsureBlockResult(updated, "updated");
    }

    private MemoryBlock createMemoryBlock(Memory memory, MemoryBlockRequest request)
        throws Exception {

        MemoryBlock block;
        if (request.initialized) {
            block = createInitializedBlock(memory, request);
        } else {
            block = createUninitializedBlock(memory, request);
        }

        applyBlockPermissions(block, request.readable, request.writable, request.executable);
        return block;
    }

    private MemoryBlock createInitializedBlock(Memory memory, MemoryBlockRequest request) throws Exception {
        return (MemoryBlock) invokeMemoryMethod(
            memory,
            "createInitializedBlock",
            new Class<?>[] { String.class, Address.class, long.class, byte.class, TaskMonitor.class, boolean.class },
            request.name,
            request.start,
            request.size,
            (byte) 0x00,
            TaskMonitor.DUMMY,
            false
        );
    }

    private MemoryBlock createUninitializedBlock(Memory memory, MemoryBlockRequest request) throws Exception {
        return (MemoryBlock) invokeMemoryMethod(
            memory,
            "createUninitializedBlock",
            new Class<?>[] { String.class, Address.class, long.class, boolean.class },
            request.name,
            request.start,
            request.size,
            false
        );
    }

    private void removeBlock(Memory memory, MemoryBlock block) throws Exception {
        invokeMemoryMethod(
            memory,
            "removeBlock",
            new Class<?>[] { MemoryBlock.class, TaskMonitor.class },
            block,
            TaskMonitor.DUMMY
        );
    }

    private void applyBlockPermissions(MemoryBlock block,
                                       boolean readable,
                                       boolean writable,
                                       boolean executable) throws Exception {
        invokeMemoryBlockMethod(block, "setRead", readable);
        invokeMemoryBlockMethod(block, "setWrite", writable);
        invokeMemoryBlockMethod(block, "setExecute", executable);
    }

    private Object invokeMemoryMethod(Memory memory,
                                      String methodName,
                                      Class<?>[] parameterTypes,
                                      Object... args) throws Exception {
        Method method = Memory.class.getMethod(methodName, parameterTypes);
        return invokeReflective(method, memory, args);
    }

    private void invokeMemoryBlockMethod(MemoryBlock block,
                                         String methodName,
                                         boolean value) throws Exception {
        Method method = MemoryBlock.class.getMethod(methodName, boolean.class);
        invokeReflective(method, block, value);
    }

    private Object invokeReflective(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private MemoryBlock findIntersectingBlock(Memory memory,
                                              Address start,
                                              Address end,
                                              MemoryBlock ignoredBlock) {
        for (MemoryBlock block : memory.getBlocks()) {
            if (ignoredBlock != null && block.equals(ignoredBlock)) {
                continue;
            }
            if (!(block.getEnd().compareTo(start) < 0 || block.getStart().compareTo(end) > 0)) {
                return block;
            }
        }
        return null;
    }

    Map<String, Object> buildMemoryBlockInfo(MemoryBlock block) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", block.getName());
        result.put("start", block.getStart().toString());
        result.put("end", block.getEnd().toString());
        result.put("size", block.getSize());
        result.put("permissions", getPermissionString(block));
        result.put("isInitialized", block.isInitialized());
        result.put("isLoaded", block.isLoaded());
        result.put("isMapped", block.isMapped());
        return result;
    }

    private void sendBlockOverlapError(HttpExchange exchange,
                                       MemoryBlock conflictingBlock,
                                       Address requestedStart,
                                       Address requestedEnd) throws IOException {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("error_code", "E_BLOCK_OVERLAP");
        details.put("conflicting_block_name", conflictingBlock.getName());
        details.put("conflicting_range_start", conflictingBlock.getStart().toString());
        details.put("conflicting_range_end", conflictingBlock.getEnd().toString());
        details.put("requested_range_start", requestedStart.toString());
        details.put("requested_range_end", requestedEnd.toString());
        details.put("required_action", "adjust range");
        sendDetailedErrorResponse(exchange, 409,
            "Requested range overlaps existing memory block",
            "OVERLAPPING_BLOCK",
            details);
    }

    private void sendMemoryBlockMutationError(HttpExchange exchange, Exception error) throws IOException {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        String causeMessage = Optional.ofNullable(cause.getMessage()).orElse(error.getMessage());
        String errorCode = "E_TRANSACTION_FAILED";
        String requiredAction = "retry";
        int statusCode = 500;

        if (cause instanceof MemoryConflictException) {
            errorCode = "E_BLOCK_OVERLAP";
            requiredAction = "adjust range";
            statusCode = 409;
        } else if (causeMessage != null) {
            String lowered = causeMessage.toLowerCase(Locale.ROOT);
            if (lowered.contains("not checked out") || lowered.contains("checkout")) {
                errorCode = "E_NOT_CHECKED_OUT";
                requiredAction = "checkout";
                statusCode = 409;
            } else if (lowered.contains("lock")) {
                errorCode = "E_PROGRAM_LOCKED";
                requiredAction = "checkout";
                statusCode = 409;
            } else if (lowered.contains("transaction")) {
                errorCode = "E_PROGRAM_LOCKED";
                requiredAction = "close transaction";
                statusCode = 409;
            }
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("error_code", errorCode);
        details.put("required_action", requiredAction);
        if (causeMessage != null && !causeMessage.isBlank()) {
            details.put("cause", causeMessage);
        }

        sendDetailedErrorResponse(exchange,
            statusCode,
            "Failed to create or update memory block: " + error.getMessage(),
            "MEMORY_BLOCK_CREATE_FAILED",
            details);
    }

    private void sendDetailedErrorResponse(HttpExchange exchange,
                                           int statusCode,
                                           String message,
                                           String code,
                                           Map<String, Object> details) throws IOException {
        ResponseBuilder builder = new ResponseBuilder(exchange, port)
            .success(false)
            .error(message, code);

        JsonObject response = builder.build();
        JsonObject errorObject = response.getAsJsonObject("error");
        if (details != null && errorObject != null) {
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                errorObject.add(entry.getKey(), new com.google.gson.Gson().toJsonTree(entry.getValue()));
            }
        }

        sendJsonResponse(exchange, response, statusCode);
    }

    private String getPermissionString(MemoryBlock block) {
        StringBuilder perms = new StringBuilder();
        perms.append(block.isRead() ? "r" : "-");
        perms.append(block.isWrite() ? "w" : "-");
        perms.append(block.isExecute() ? "x" : "-");
        perms.append(block.isVolatile() ? "v" : "-");
        return perms.toString();
    }
}