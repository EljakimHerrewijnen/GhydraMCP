package eu.starsong.ghidra.endpoints;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import eu.starsong.ghidra.util.DecompilerCache;
import eu.starsong.ghidra.util.GhidraUtil;
import eu.starsong.ghidra.util.HttpUtil;
import eu.starsong.ghidra.util.TransactionHelper;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.pcode.HighFunction;
import ghidra.util.Msg;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutomationEndpoints extends AbstractEndpoint {

    private final PluginTool tool;
    private final DecompilerCache decompilerCache;
    private final Gson gson = new Gson();

    public AutomationEndpoints(Program program, int port, PluginTool tool, DecompilerCache decompilerCache) {
        super(program, port, decompilerCache);
        this.tool = tool;
        this.decompilerCache = decompilerCache;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        server.createContext("/capabilities", HttpUtil.safeHandler(this::handleCapabilities, port));
        server.createContext("/transactions/run-batch", HttpUtil.safeHandler(this::handleRunBatch, port));
    }

    private void handleCapabilities(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
            return;
        }

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("ensure_memory_block", true);
        features.put("ensure_data", true);
        features.put("project_file_schema_v2", true);
        features.put("variable_ids", true);
        features.put("variable_mutation_by_id", true);
        features.put("address_targeted_function_writes", true);
        features.put("run_batch", true);
        features.put("dossier", true);

        List<String> batchOps = new ArrayList<>();
        batchOps.add("ensure_memory_block");
        batchOps.add("ensure_data");
        batchOps.add("update_variable");
        batchOps.add("apply_struct");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverVersion", Optional.ofNullable(getClass().getPackage().getImplementationVersion()).orElse("unknown"));
        result.put("programLoaded", getCurrentProgram() != null);
        result.put("features", features);
        result.put("supportedBatchOperations", batchOps);

        ResponseBuilder builder = new ResponseBuilder(exchange, port)
            .success(true)
            .result(result);
        builder.addLink("self", "/capabilities");
        builder.addLink("run_batch", "/transactions/run-batch", "POST");

        sendJsonResponse(exchange, builder.build(), 200);
    }

    private void handleRunBatch(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
            return;
        }

        Program program = getCurrentProgram();
        if (program == null) {
            sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
            return;
        }

        JsonObject payload;
        try {
            payload = parseJsonBody(exchange);
        } catch (Exception e) {
            sendErrorResponse(exchange, 400, "Invalid JSON request body: " + e.getMessage(), "INVALID_REQUEST");
            return;
        }

        JsonArray ops = payload.has("ops") && payload.get("ops").isJsonArray() ? payload.getAsJsonArray("ops") : null;
        if (ops == null || ops.size() == 0) {
            sendErrorResponse(exchange, 400, "Missing required array parameter: ops", "MISSING_PARAMETER");
            return;
        }

        boolean rollbackOnError = !payload.has("rollback_on_error") || payload.get("rollback_on_error").getAsBoolean();
        List<Map<String, Object>> operationResults = new ArrayList<>();
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        AtomicBoolean transactionFailed = new AtomicBoolean(false);
        String transactionError = null;

        MemoryEndpoints memoryEndpoints = new MemoryEndpoints(program, port, tool);
        DataEndpoints dataEndpoints = new DataEndpoints(program, port, tool);
        FunctionEndpoints functionEndpoints = new FunctionEndpoints(program, port, tool, decompilerCache);

        try {
            TransactionHelper.executeInTransaction(program, "Run batch operations", () -> {
                Memory memory = program.getMemory();

                for (int index = 0; index < ops.size(); index++) {
                    JsonObject operation = getJsonObject(ops.get(index), "ops[" + index + "]");
                    String opName = getRequiredString(operation, "op");

                    Map<String, Object> opStatus = new LinkedHashMap<>();
                    opStatus.put("index", index);
                    opStatus.put("op", opName);

                    try {
                        Map<String, Object> opResult = executeBatchOperation(
                            operation,
                            opName,
                            program,
                            memory,
                            memoryEndpoints,
                            dataEndpoints,
                            functionEndpoints
                        );
                        opStatus.put("success", true);
                        opStatus.put("result", opResult);
                    } catch (Exception e) {
                        transactionFailed.set(true);
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("message", e.getMessage());
                        error.put("code", classifyBatchError(e));
                        opStatus.put("success", false);
                        opStatus.put("error", error);
                        operationResults.add(opStatus);

                        if (rollbackOnError) {
                            rolledBack.set(true);
                            throw e;
                        }

                        continue;
                    }

                    operationResults.add(opStatus);
                }

                return true;
            });
        } catch (TransactionHelper.TransactionException e) {
            transactionFailed.set(true);
            transactionError = e.getMessage();
            Msg.error(this, "Batch transaction failed", e);
        }

        int successCount = 0;
        int failureCount = 0;
        for (Map<String, Object> status : operationResults) {
            if (Boolean.TRUE.equals(status.get("success"))) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operations", operationResults);
        result.put("rollbackOnError", rollbackOnError);
        result.put("rolledBack", rolledBack.get());
        result.put("appliedCount", successCount);
        result.put("failedCount", failureCount);
        if (transactionError != null) {
            result.put("transactionError", transactionError);
        }

        boolean success = !transactionFailed.get() && failureCount == 0;
        ResponseBuilder builder = new ResponseBuilder(exchange, port)
            .success(success)
            .result(result);
        builder.addLink("self", "/transactions/run-batch");
        builder.addLink("capabilities", "/capabilities");

        if (!success) {
            builder.error(
                rolledBack.get() ? "Batch transaction rolled back after an operation failed" : "Batch transaction completed with failures",
                "BATCH_FAILED"
            );
        }

        sendJsonResponse(exchange, builder.build(), rolledBack.get() ? 409 : 200);
    }

    private Map<String, Object> executeBatchOperation(JsonObject operation,
                                                      String opName,
                                                      Program program,
                                                      Memory memory,
                                                      MemoryEndpoints memoryEndpoints,
                                                      DataEndpoints dataEndpoints,
                                                      FunctionEndpoints functionEndpoints) throws Exception {
        switch (opName) {
            case "ensure_memory_block":
                return runEnsureMemoryBlock(operation, program, memory, memoryEndpoints);
            case "ensure_data":
                return runEnsureData(operation, program, dataEndpoints);
            case "update_variable":
            case "functions_update_variable":
                return runUpdateVariable(operation, program, functionEndpoints);
            case "apply_struct":
            case "functions_apply_struct":
                return runApplyStruct(operation, program, functionEndpoints);
            default:
                throw new IllegalArgumentException("Unsupported batch operation: " + opName);
        }
    }

    private Map<String, Object> runEnsureMemoryBlock(JsonObject operation,
                                                     Program program,
                                                     Memory memory,
                                                     MemoryEndpoints memoryEndpoints) throws Exception {
        String name = getRequiredString(operation, "name");
        String addressStr = getOptionalString(operation, "address", "start");
        if (addressStr == null || addressStr.isBlank()) {
            throw new IllegalArgumentException("ensure_memory_block requires address or start");
        }

        long size = getRequiredLong(operation, "size");
        Address start = program.getAddressFactory().getAddress(addressStr);
        if (start == null) {
            throw new IllegalArgumentException("Invalid address format: " + addressStr);
        }

        MemoryEndpoints.EnsureBlockResult ensureResult = memoryEndpoints.ensureMemoryBlockInTransaction(
            memory,
            name,
            start,
            size,
            getOptionalBoolean(operation, "readable", true),
            getOptionalBoolean(operation, "writable", false),
            getOptionalBoolean(operation, "executable", false),
            getOptionalBoolean(operation, "initialized", false)
        );

        Map<String, Object> result = memoryEndpoints.buildMemoryBlockInfo(ensureResult.getBlock());
        result.put("action", ensureResult.getAction());
        result.put("created", "created".equals(ensureResult.getAction()));
        result.put("updated", "updated".equals(ensureResult.getAction()));
        result.put("unchanged", "unchanged".equals(ensureResult.getAction()));
        return result;
    }

    private Map<String, Object> runEnsureData(JsonObject operation,
                                              Program program,
                                              DataEndpoints dataEndpoints) throws Exception {
        String addressStr = getRequiredString(operation, "address");
        String dataTypeStr = getRequiredString(operation, "type");
        Integer size = getOptionalInteger(operation, "size");
        String label = getOptionalString(operation, "label", "name");
        boolean clearConflicts = getOptionalBoolean(operation, "clear_conflicts", true);
        return dataEndpoints.ensureData(program, addressStr, dataTypeStr, size, label, clearConflicts);
    }

    private Map<String, Object> runUpdateVariable(JsonObject operation,
                                                  Program program,
                                                  FunctionEndpoints functionEndpoints) throws Exception {
        Function function = resolveFunction(program, operation);
        String variableName = getOptionalString(operation, "variable_name");
        String variableId = getOptionalString(operation, "variable_id");
        if ((variableName == null || variableName.isBlank()) && (variableId == null || variableId.isBlank())) {
            throw new IllegalArgumentException("update_variable requires variable_name or variable_id");
        }

        String newName = getOptionalString(operation, "new_name");
        String dataTypeName = getOptionalString(operation, "data_type", "type");
        if ((newName == null || newName.isBlank()) && (dataTypeName == null || dataTypeName.isBlank())) {
            throw new IllegalArgumentException("update_variable requires new_name or data_type");
        }

        DataType targetDataType = null;
        if (dataTypeName != null && !dataTypeName.isBlank()) {
            targetDataType = GhidraUtil.resolveDataType(program, dataTypeName);
            if (targetDataType == null) {
                throw new IllegalArgumentException("Data type not found: " + dataTypeName);
            }
        }

        HighFunction highFunction = loadHighFunction(function, program);
        Map<String, Object> result = functionEndpoints.updateFunctionVariable(
            function,
            variableName,
            variableId,
            newName,
            targetDataType,
            highFunction
        );
        if (result == null) {
            throw new IllegalArgumentException("Function variable not found");
        }
        invalidateDecompiler(function);
        return result;
    }

    private Map<String, Object> runApplyStruct(JsonObject operation,
                                               Program program,
                                               FunctionEndpoints functionEndpoints) throws Exception {
        Function function = resolveFunction(program, operation);
        String variableName = getOptionalString(operation, "variable_name");
        String variableId = getOptionalString(operation, "variable_id");
        if ((variableName == null || variableName.isBlank()) && (variableId == null || variableId.isBlank())) {
            throw new IllegalArgumentException("apply_struct requires variable_name or variable_id");
        }

        String structName = getRequiredString(operation, "struct_name");
        boolean asPointer = getOptionalBoolean(operation, "as_pointer", true);
        String newName = getOptionalString(operation, "new_name");

        DataType structType = functionEndpoints.resolveStructDataType(program, structName);
        if (!(structType instanceof Structure)) {
            throw new IllegalArgumentException("Struct not found: " + structName);
        }

        DataType targetType = structType;
        if (asPointer) {
            DataType pointerCandidate = GhidraUtil.resolveDataType(program, structType.getName() + " *");
            targetType = pointerCandidate != null ? pointerCandidate : new PointerDataType(structType);
        }

        HighFunction highFunction = loadHighFunction(function, program);
        Map<String, Object> result = functionEndpoints.updateFunctionVariable(
            function,
            variableName,
            variableId,
            newName,
            targetType,
            highFunction
        );
        if (result == null) {
            throw new IllegalArgumentException("Function variable not found");
        }

        result.put("applied_struct", structType.getName());
        result.put("applied_as_pointer", asPointer);
        invalidateDecompiler(function);
        return result;
    }

    private Function resolveFunction(Program program, JsonObject operation) throws Exception {
        String functionAddress = getOptionalString(operation, "function_address", "address");
        if (functionAddress != null && !functionAddress.isBlank()) {
            Address address = program.getAddressFactory().getAddress(functionAddress);
            if (address == null) {
                throw new IllegalArgumentException("Invalid function address: " + functionAddress);
            }

            Function function = program.getFunctionManager().getFunctionAt(address);
            if (function == null) {
                function = program.getFunctionManager().getFunctionContaining(address);
            }
            if (function == null) {
                throw new IllegalArgumentException("Function not found at address: " + functionAddress);
            }
            return function;
        }

        String functionName = getOptionalString(operation, "function_name", "name");
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("Function operation requires function_address or function_name");
        }

        Function match = null;
        for (Function function : program.getFunctionManager().getFunctions(true)) {
            if (!functionName.equals(function.getName())) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException("Multiple functions matched name: " + functionName + "; use function_address");
            }
            match = function;
        }

        if (match == null) {
            throw new IllegalArgumentException("Function not found with name: " + functionName);
        }
        return match;
    }

    private HighFunction loadHighFunction(Function function, Program program) {
        if (decompilerCache != null) {
            DecompileResults results = decompilerCache.getDecompileResults(function, 30);
            if (results != null && results.decompileCompleted()) {
                return results.getHighFunction();
            }
        }

        DecompInterface decomp = new DecompInterface();
        try {
            decomp.openProgram(program);
            DecompileResults results = decomp.decompileFunction(function, 30, new ConsoleTaskMonitor());
            if (results != null && results.decompileCompleted()) {
                return results.getHighFunction();
            }
        } catch (Exception e) {
            Msg.warn(this, "Failed to load high function for batch operation: " + e.getMessage());
        } finally {
            decomp.dispose();
        }
        return null;
    }

    private void invalidateDecompiler(Function function) {
        if (decompilerCache != null) {
            decompilerCache.invalidate(function.getEntryPoint());
        }
    }

    private JsonObject parseJsonBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        JsonObject json = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        return json != null ? json : new JsonObject();
    }

    private JsonObject getJsonObject(JsonElement element, String description) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(description + " must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    private String getRequiredString(JsonObject json, String key) {
        String value = getOptionalString(json, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return value;
    }

    private String getOptionalString(JsonObject json, String... keys) {
        for (String key : keys) {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsString();
            }
        }
        return null;
    }

    private long getRequiredLong(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return json.get(key).getAsLong();
    }

    private Integer getOptionalInteger(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return Integer.valueOf(json.get(key).getAsInt());
    }

    private boolean getOptionalBoolean(JsonObject json, String key, boolean defaultValue) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return defaultValue;
        }
        return json.get(key).getAsBoolean();
    }

    private String classifyBatchError(Exception error) {
        String message = error.getMessage();
        if (message == null) {
            return "INTERNAL_ERROR";
        }

        String lowered = message.toLowerCase();
        if (lowered.contains("not found")) {
            return "NOT_FOUND";
        }
        if (lowered.contains("multiple functions matched")) {
            return "AMBIGUOUS_TARGET";
        }
        if (lowered.contains("missing required parameter") || lowered.contains("requires")) {
            return "MISSING_PARAMETER";
        }
        if (lowered.contains("invalid")) {
            return "INVALID_PARAMETER";
        }
        return "OPERATION_FAILED";
    }
}