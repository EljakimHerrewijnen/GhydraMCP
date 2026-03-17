package eu.starsong.ghidra.endpoints;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import eu.starsong.ghidra.util.TransactionHelper;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Endpoints for managing custom data types (structs, unions, enums).
 * Implements HATEOAS-compliant REST API for data type manipulation.
 */
public class DataTypeEndpoints extends AbstractEndpoint {

    private PluginTool tool;

    public DataTypeEndpoints(Program program, int port) {
        super(program, port);
    }

    public DataTypeEndpoints(Program program, int port, PluginTool tool) {
        super(program, port);
        this.tool = tool;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        server.createContext("/datatypes", this::handleDataTypes);
        server.createContext("/datatypes/struct", this::handleCreateStruct);
        server.createContext("/datatypes/enum", this::handleCreateEnum);
        server.createContext("/datatypes/union", this::handleCreateUnion);
    }

    /**
     * Handle GET /datatypes - List all data types
     */
    private void handleDataTypes(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getCurrentProgram();
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseQueryParams(exchange);
            Map<String, String> robustParams = parseQueryParamsRobust(exchange.getRequestURI().getRawQuery());
            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);
            String category = firstNonBlank(params.get("category"), robustParams.get("category"));
            String kind = firstNonBlank(params.get("kind"), robustParams.get("kind")); // struct, enum, union
            String name = firstNonBlank(params.get("name"), robustParams.get("name"));

            DataTypeManager dtm = program.getDataTypeManager();

            if (name != null && !name.isEmpty()) {
                DataType found = findDataTypeByName(dtm, name, category, kind);
                if (found == null) {
                    sendErrorResponse(exchange, 404, "Data type not found: " + name, "RESOURCE_NOT_FOUND");
                    return;
                }

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                        .success(true)
                        .result(toDataTypeInfo(found));

                builder.addLink("self", "/datatypes?name=" + name);
                builder.addLink("datatypes", "/datatypes");
                builder.addLink("create_struct", "/datatypes/struct", "POST");
                builder.addLink("create_enum", "/datatypes/enum", "POST");
                builder.addLink("create_union", "/datatypes/union", "POST");

                sendJsonResponse(exchange, builder.build(), 200);
                return;
            }

            List<Map<String, Object>> dataTypes = new ArrayList<>();

            // Iterate through all data types
            Iterator<DataType> iterator = dtm.getAllDataTypes();
            while (iterator.hasNext()) {
                DataType dt = iterator.next();

                // Apply filters
                if (!matchesFilters(dt, category, kind)) {
                    continue;
                }

                dataTypes.add(toDataTypeInfo(dt));
            }

            // Build response
            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true);

            // Apply pagination
            List<Map<String, Object>> paginated = applyPagination(
                    dataTypes, offset, limit, builder, "/datatypes",
                    buildQueryString(params));

            builder.result(paginated);
            builder.addLink("self", "/datatypes");
            builder.addLink("create_struct", "/datatypes/struct", "POST");
            builder.addLink("create_enum", "/datatypes/enum", "POST");
            builder.addLink("create_union", "/datatypes/union", "POST");

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private DataType findDataTypeByName(DataTypeManager dtm, String name, String category, String kind) {
        Iterator<DataType> iterator = dtm.getAllDataTypes();
        while (iterator.hasNext()) {
            DataType dt = iterator.next();
            if (!name.equals(dt.getName())) {
                continue;
            }
            if (!matchesFilters(dt, category, kind)) {
                continue;
            }
            return dt;
        }
        return null;
    }

    private boolean matchesFilters(DataType dt, String category, String kind) {
        if (category != null && !dt.getCategoryPath().getPath().contains(category)) {
            return false;
        }

        if (kind == null) {
            return true;
        }

        return ("struct".equals(kind) && dt instanceof Structure)
                || ("enum".equals(kind) && dt instanceof ghidra.program.model.data.Enum)
                || ("union".equals(kind) && dt instanceof Union);
    }

    private Map<String, Object> toDataTypeInfo(DataType dt) {
        Map<String, Object> dtInfo = new HashMap<>();
        dtInfo.put("name", dt.getName());
        dtInfo.put("displayName", dt.getDisplayName());
        dtInfo.put("category", dt.getCategoryPath().getPath());
        dtInfo.put("length", dt.getLength());

        if (dt instanceof Structure) {
            dtInfo.put("kind", "struct");
            dtInfo.put("numComponents", ((Structure) dt).getNumComponents());
        } else if (dt instanceof ghidra.program.model.data.Enum) {
            dtInfo.put("kind", "enum");
            dtInfo.put("numValues", ((ghidra.program.model.data.Enum) dt).getCount());
        } else if (dt instanceof Union) {
            dtInfo.put("kind", "union");
            dtInfo.put("numComponents", ((Union) dt).getNumComponents());
        } else {
            dtInfo.put("kind", "other");
        }

        return dtInfo;
    }

    /**
     * Handle POST /datatypes/struct - Create a new structure
     */
    private void handleCreateStruct(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getCurrentProgram();
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String name = params.get("name");
            String fieldsJson = params.get("fields"); // JSON array of {name, type, size}
            String category = params.getOrDefault("category", "/");

            if (name == null || name.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: name", "MISSING_PARAMETER");
                return;
            }

            try {
                Map<String, Object> result = TransactionHelper.executeInTransaction(
                        program, "Create struct " + name, () -> {
                            DataTypeManager dtm = program.getDataTypeManager();

                            // Create the structure
                            CategoryPath categoryPath = new CategoryPath(category);
                            Structure struct = new StructureDataType(categoryPath, name, 0, dtm);

                            // Parse and add fields if provided
                            if (fieldsJson != null && !fieldsJson.isEmpty()) {
                                // TODO: Parse JSON fields array and add components
                                // For now, create an empty struct
                                Msg.info(this, "Fields parsing not yet implemented, creating empty struct");
                            }

                            // Add to data type manager
                            DataType added = dtm.addDataType(struct, null);

                            // Build result
                            Map<String, Object> resultMap = new HashMap<>();
                            resultMap.put("name", added.getName());
                            resultMap.put("category", added.getCategoryPath().getPath());
                            resultMap.put("length", added.getLength());
                            resultMap.put("kind", "struct");

                            return resultMap;
                        });

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                        .success(true)
                        .result(result);

                builder.addLink("self", "/datatypes/struct");
                builder.addLink("datatypes", "/datatypes");

                sendJsonResponse(exchange, builder.build(), 201);

            } catch (Exception e) {
                Msg.error(this, "Error creating struct", e);
                sendErrorResponse(exchange, 500, "Failed to create struct: " + e.getMessage());
            }

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes/struct endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /datatypes/enum - Create a new enumeration
     */
    private void handleCreateEnum(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getCurrentProgram();
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String name = params.get("name");
            String valuesJson = params.get("values"); // JSON object of {name: value}
            String category = params.getOrDefault("category", "/");
            int size = parseIntOrDefault(params.get("size"), 4); // Default to 4 bytes

            if (name == null || name.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: name", "MISSING_PARAMETER");
                return;
            }

            try {
                Map<String, Object> result = TransactionHelper.executeInTransaction(
                        program, "Create enum " + name, () -> {
                            DataTypeManager dtm = program.getDataTypeManager();

                            // Create the enum
                            CategoryPath categoryPath = new CategoryPath(category);
                            EnumDataType enumDt = new EnumDataType(categoryPath, name, size, dtm);

                            // Parse and add values if provided
                            if (valuesJson != null && !valuesJson.isEmpty()) {
                                // TODO: Parse JSON values and add enum members
                                // For now, create an empty enum
                                Msg.info(this, "Values parsing not yet implemented, creating empty enum");
                            }

                            // Add to data type manager
                            DataType added = dtm.addDataType(enumDt, null);

                            // Build result
                            Map<String, Object> resultMap = new HashMap<>();
                            resultMap.put("name", added.getName());
                            resultMap.put("category", added.getCategoryPath().getPath());
                            resultMap.put("length", added.getLength());
                            resultMap.put("kind", "enum");

                            return resultMap;
                        });

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                        .success(true)
                        .result(result);

                builder.addLink("self", "/datatypes/enum");
                builder.addLink("datatypes", "/datatypes");

                sendJsonResponse(exchange, builder.build(), 201);

            } catch (Exception e) {
                Msg.error(this, "Error creating enum", e);
                sendErrorResponse(exchange, 500, "Failed to create enum: " + e.getMessage());
            }

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes/enum endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /datatypes/union - Create a new union
     */
    private void handleCreateUnion(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getCurrentProgram();
            if (program == null) {
                sendErrorResponse(exchange, 400, "No program loaded", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String name = params.get("name");
            String fieldsJson = params.get("fields"); // JSON array of {name, type}
            String category = params.getOrDefault("category", "/");

            if (name == null || name.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: name", "MISSING_PARAMETER");
                return;
            }

            try {
                Map<String, Object> result = TransactionHelper.executeInTransaction(
                        program, "Create union " + name, () -> {
                            DataTypeManager dtm = program.getDataTypeManager();

                            // Create the union
                            CategoryPath categoryPath = new CategoryPath(category);
                            UnionDataType union = new UnionDataType(categoryPath, name, dtm);

                            // Parse and add fields if provided
                            if (fieldsJson != null && !fieldsJson.isEmpty()) {
                                // TODO: Parse JSON fields array and add components
                                // For now, create an empty union
                                Msg.info(this, "Fields parsing not yet implemented, creating empty union");
                            }

                            // Add to data type manager
                            DataType added = dtm.addDataType(union, null);

                            // Build result
                            Map<String, Object> resultMap = new HashMap<>();
                            resultMap.put("name", added.getName());
                            resultMap.put("category", added.getCategoryPath().getPath());
                            resultMap.put("length", added.getLength());
                            resultMap.put("kind", "union");

                            return resultMap;
                        });

                ResponseBuilder builder = new ResponseBuilder(exchange, port)
                        .success(true)
                        .result(result);

                builder.addLink("self", "/datatypes/union");
                builder.addLink("datatypes", "/datatypes");

                sendJsonResponse(exchange, builder.build(), 201);

            } catch (Exception e) {
                Msg.error(this, "Error creating union", e);
                sendErrorResponse(exchange, 500, "Failed to create union: " + e.getMessage());
            }

        } catch (Exception e) {
            Msg.error(this, "Error in /datatypes/union endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Build query string from parameters
     */
    private String buildQueryString(Map<String, String> params) {
        StringBuilder query = new StringBuilder();

        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().equals("offset") || entry.getKey().equals("limit")) {
                continue; // Skip pagination params
            }
            if (query.length() > 0) {
                query.append("&");
            }
            query.append(entry.getKey()).append("=").append(entry.getValue());
        }

        return query.toString();
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary;
        }
        return fallback;
    }

    private Map<String, String> parseQueryParamsRobust(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }

        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }

            String[] kv = pair.split("=", 2);
            String key = urlDecode(kv[0]);
            String value = kv.length > 1 ? urlDecode(kv[1]) : "";
            result.put(key, value);
        }

        return result;
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
