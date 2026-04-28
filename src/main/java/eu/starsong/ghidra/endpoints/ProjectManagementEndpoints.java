package eu.starsong.ghidra.endpoints;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.starsong.ghidra.api.ResponseBuilder;
import ghidra.app.services.ProgramManager;
import ghidra.framework.data.CheckinHandler;
import ghidra.framework.model.*;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Endpoints for project management operations (list projects, browse files, open files).
 * Implements HATEOAS-compliant REST API for Ghidra project interaction.
 */
public class ProjectManagementEndpoints extends AbstractEndpoint {

    private PluginTool tool;
    private static final AtomicLong OP_COUNTER = new AtomicLong(1);

    public ProjectManagementEndpoints(Program program, int port) {
        super(program, port);
    }

    public ProjectManagementEndpoints(Program program, int port, PluginTool tool) {
        super(program, port);
        this.tool = tool;
    }

    @Override
    protected PluginTool getTool() {
        return tool;
    }

    @Override
    public void registerEndpoints(HttpServer server) {
        server.createContext("/project", this::handleCurrentProject);
        server.createContext("/project/files", this::handleListProjectFiles);
        server.createContext("/project/open", this::handleOpenFile);
        server.createContext("/server/status", this::handleServerStatus);
        server.createContext("/server/version_control/sync", this::handleSyncFile);
        server.createContext("/server/version_control/sync-bulk", this::handleSyncBulk);
        server.createContext("/server/version_control/sync-preflight", this::handleSyncPreflight);
        server.createContext("/server/version_control/sync-current", this::handleSyncCurrentProgram);
        server.createContext("/server/version_control/checkout", this::handleCheckoutFile);
        server.createContext("/server/version_control/checkin", this::handleCheckinFile);
        server.createContext("/server/version_control/undo_checkout", this::handleUndoCheckoutFile);
        server.createContext("/server/version_control/file-status", this::handleFileStatus);
        server.createContext("/server/repository/files", this::handleRepositoryFiles);
        server.createContext("/server/version_history", this::handleVersionHistory);
    }

    /**
     * Handle GET /project - Get current project information
     */
    private void handleCurrentProject(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Project project = tool.getProject();
            if (project == null) {
                sendErrorResponse(exchange, 503, "No project is currently open", "NO_PROJECT_OPEN");
                return;
            }

            Map<String, Object> projectInfo = new HashMap<>();
            projectInfo.put("name", project.getName());

            ProjectLocator locator = project.getProjectLocator();
            if (locator != null) {
                projectInfo.put("location", locator.getLocation());
                projectInfo.put("projectPath", locator.getProjectDir().getAbsolutePath());
            }

            ProjectData projectData = project.getProjectData();
            if (projectData != null) {
                DomainFolder rootFolder = projectData.getRootFolder();
                projectInfo.put("rootPath", rootFolder.getPathname());

                // Count files and folders
                int[] counts = countFilesAndFolders(rootFolder);
                projectInfo.put("fileCount", counts[0]);
                projectInfo.put("folderCount", counts[1]);
            }

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true)
                    .result(projectInfo);

            builder.addLink("self", "/project");
            builder.addLink("files", "/project/files");
            builder.addLink("programs", "/programs");

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in /project endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle GET /project/files - List files in current project
     */
    private void handleListProjectFiles(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Map<String, String> params = parseQueryParams(exchange);
            String folderPath = params.getOrDefault("folder", "/");
            boolean recursive = Boolean.parseBoolean(params.getOrDefault("recursive", "true"));
            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);

            Project project = tool.getProject();
            if (project == null) {
                sendErrorResponse(exchange, 503, "No project is currently open", "NO_PROJECT_OPEN");
                return;
            }

            ProjectData projectData = project.getProjectData();
            DomainFolder folder = projectData.getFolder(folderPath);

            if (folder == null) {
                sendErrorResponse(exchange, 404, "Folder not found: " + folderPath, "FOLDER_NOT_FOUND");
                return;
            }

            List<Map<String, Object>> items = new ArrayList<>();

            if (recursive) {
                // Collect all files recursively
                List<DomainFile> allFiles = new ArrayList<>();
                collectDomainFiles(folder, allFiles);

                for (DomainFile file : allFiles) {
                    items.add(createFileInfo(file, project));
                }
            } else {
                // Just list current folder contents
                for (DomainFolder subFolder : folder.getFolders()) {
                    Map<String, Object> folderInfo = new HashMap<>();
                    folderInfo.put("name", subFolder.getName());
                    folderInfo.put("path", subFolder.getPathname());
                    folderInfo.put("type", "folder");
                    items.add(folderInfo);
                }

                for (DomainFile file : folder.getFiles()) {
                    items.add(createFileInfo(file, project));
                }
            }

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true);

            // Apply pagination
            List<Map<String, Object>> paginated = applyPagination(
                    items, offset, limit, builder, "/project/files",
                    "folder=" + folderPath + "&recursive=" + recursive);

            Map<String, Object> result = new HashMap<>();
            result.put("project", project.getName());
            result.put("folder", folderPath);
            result.put("recursive", recursive);
            result.put("items", paginated);

            builder.result(result);
            builder.addLink("self", "/project/files");
            builder.addLink("project", "/project");

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in /project/files endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /project/open - Open a file in CodeBrowser
     */
    private void handleOpenFile(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String filePath = params.get("path");

            if (filePath == null || filePath.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: path", "MISSING_PARAMETER");
                return;
            }

            Project project = tool.getProject();
            if (project == null) {
                sendErrorResponse(exchange, 503, "No project is currently open", "NO_PROJECT_OPEN");
                return;
            }

            ProjectData projectData = project.getProjectData();
            DomainFile file = projectData.getFile(filePath);

            if (file == null) {
                sendErrorResponse(exchange, 404, "File not found: " + filePath, "FILE_NOT_FOUND");
                return;
            }

            // Open the file using ProgramManager
            ProgramManager programManager = tool.getService(ProgramManager.class);
            if (programManager == null) {
                sendErrorResponse(exchange, 503, "ProgramManager service not available", "SERVICE_UNAVAILABLE");
                return;
            }

            // Open the program with OPEN_CURRENT to avoid triggering analysis dialog
            // Using the current version and programManager as the consumer
            Program program = programManager.openProgram(file, DomainFile.DEFAULT_VERSION, ProgramManager.OPEN_CURRENT);

            if (program == null) {
                sendErrorResponse(exchange, 500, "Failed to open file: " + filePath, "OPEN_FAILED");
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("path", filePath);
            result.put("name", file.getName());
            result.put("opened", true);
            result.put("message", "File opened in CodeBrowser. Use instances_discover to find the new instance.");

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                    .success(true)
                    .result(result);

            builder.addLink("self", "/project/open");
            builder.addLink("file", "/project/files?path=" + filePath);
            builder.addLink("instances", "/instances");

            sendJsonResponse(exchange, builder.build(), 200);

        } catch (Exception e) {
            Msg.error(this, "Error in /project/open endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle GET /server/status - check whether current project is shared and connected.
     */
    private void handleServerStatus(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Project project = tool.getProject();
            if (project == null) {
                sendErrorResponse(exchange, 503, "No project is currently open", "NO_PROJECT_OPEN");
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("project", project.getName());

            Object repo = getProjectRepository();
            boolean shared = repo != null;
            result.put("shared", shared);
            if (repo != null) {
                try {
                    java.lang.reflect.Method connectedMethod = repo.getClass().getMethod("isConnected");
                    Object connected = connectedMethod.invoke(repo);
                    result.put("connected", connected instanceof Boolean ? (Boolean) connected : false);

                    java.lang.reflect.Method infoMethod = repo.getClass().getMethod("getServerInfo");
                    Object serverInfo = infoMethod.invoke(repo);
                    result.put("serverInfo", String.valueOf(serverInfo));
                } catch (Exception e) {
                    result.put("connected", false);
                    result.put("serverError", e.getMessage());
                }
            } else {
                result.put("connected", false);
            }

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(result);
            builder.addLink("self", "/server/status");
            builder.addLink("sync", "/server/version_control/sync", "POST");
            builder.addLink("sync_current", "/server/version_control/sync-current", "POST");

            sendJsonResponse(exchange, builder.build(), 200);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/status endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /server/version_control/sync - sync specific project file to remote server repository.
     */
    private void handleSyncFile(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String filePath = params.get("path");
            if (filePath == null || filePath.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: path", "MISSING_PARAMETER");
                return;
            }

            String comment = params.getOrDefault("comment", "Synced via GhydraMCP");
            boolean autoAdd = Boolean.parseBoolean(params.getOrDefault("auto_add", "true"));
            boolean keepCheckedOut = Boolean.parseBoolean(params.getOrDefault("keep_checked_out", "false"));
            boolean exclusiveCheckout = Boolean.parseBoolean(params.getOrDefault("exclusive_checkout", "false"));
            boolean force = Boolean.parseBoolean(params.getOrDefault("force", "false"));
            boolean failIfModified = Boolean.parseBoolean(params.getOrDefault("fail_if_modified", "false"));
            boolean allowMerge = Boolean.parseBoolean(params.getOrDefault("allow_merge", "true"));

            Project project = tool.getProject();
            if (project == null) {
                sendErrorResponse(exchange, 503, "No project is currently open", "NO_PROJECT_OPEN");
                return;
            }

            ProjectData projectData = project.getProjectData();
            DomainFile file = projectData.getFile(filePath);
            if (file == null) {
                sendErrorResponse(exchange, 404, "File not found: " + filePath, "FILE_NOT_FOUND");
                return;
            }

            Map<String, Object> syncResult = syncDomainFileToServer(file, comment, autoAdd, keepCheckedOut,
                exclusiveCheckout, force, failIfModified, allowMerge);

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(syncResult);
            builder.addLink("self", "/server/version_control/sync");
            builder.addLink("status", "/server/status");
            builder.addLink("file", "/project/files?folder=" + file.getParent().getPathname());

            sendJsonResponse(exchange, builder.build(), 200);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_control/sync endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * Handle POST /server/version_control/sync-current - sync current program file to remote server repository.
     */
    private void handleSyncCurrentProgram(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Program program = getCurrentProgram();
            if (program == null || program.getDomainFile() == null) {
                sendErrorResponse(exchange, 400, "No current program domain file is available", "NO_PROGRAM_LOADED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String comment = params.getOrDefault("comment", "Synced via GhydraMCP");
            boolean autoAdd = Boolean.parseBoolean(params.getOrDefault("auto_add", "true"));
            boolean keepCheckedOut = Boolean.parseBoolean(params.getOrDefault("keep_checked_out", "false"));
            boolean exclusiveCheckout = Boolean.parseBoolean(params.getOrDefault("exclusive_checkout", "false"));
            boolean force = Boolean.parseBoolean(params.getOrDefault("force", "false"));
            boolean failIfModified = Boolean.parseBoolean(params.getOrDefault("fail_if_modified", "false"));
            boolean allowMerge = Boolean.parseBoolean(params.getOrDefault("allow_merge", "true"));

            DomainFile file = program.getDomainFile();
            Map<String, Object> syncResult = syncDomainFileToServer(file, comment, autoAdd, keepCheckedOut,
                exclusiveCheckout, force, failIfModified, allowMerge);
            syncResult.put("currentProgram", program.getName());

            ResponseBuilder builder = new ResponseBuilder(exchange, port)
                .success(true)
                .result(syncResult);
            builder.addLink("self", "/server/version_control/sync-current");
            builder.addLink("status", "/server/status");
            builder.addLink("program", "/program");

            sendJsonResponse(exchange, builder.build(), 200);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_control/sync-current endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleSyncBulk(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Map<String, String> params = parseJsonPostParams(exchange);
            String pathsCsv = params.get("paths");
            if (pathsCsv == null || pathsCsv.trim().isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing required parameter: paths", "MISSING_PARAMETER");
                return;
            }

            String comment = params.getOrDefault("comment", "Synced via GhydraMCP");
            boolean autoAdd = Boolean.parseBoolean(params.getOrDefault("auto_add", "true"));
            boolean keepCheckedOut = Boolean.parseBoolean(params.getOrDefault("keep_checked_out", "false"));
            boolean exclusiveCheckout = Boolean.parseBoolean(params.getOrDefault("exclusive_checkout", "false"));
            boolean force = Boolean.parseBoolean(params.getOrDefault("force", "false"));
            boolean failIfModified = Boolean.parseBoolean(params.getOrDefault("fail_if_modified", "false"));
            boolean allowMerge = Boolean.parseBoolean(params.getOrDefault("allow_merge", "true"));
            boolean continueOnError = Boolean.parseBoolean(params.getOrDefault("continue_on_error", "true"));

            Project project = tool.getProject();
            if (project == null) {
                sendErrorResponse(exchange, 503, "No project is currently open", "NO_PROJECT_OPEN");
                return;
            }

            List<Map<String, Object>> results = new ArrayList<>();
            String[] paths = pathsCsv.split(",");
            int succeeded = 0;
            for (String rawPath : paths) {
                String filePath = rawPath.trim();
                if (filePath.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("path", filePath);
                try {
                    DomainFile file = project.getProjectData().getFile(filePath);
                    if (file == null) {
                        throw new IllegalStateException("File not found");
                    }
                    Map<String, Object> syncResult = syncDomainFileToServer(file, comment, autoAdd,
                        keepCheckedOut, exclusiveCheckout, force, failIfModified, allowMerge);
                    item.put("success", true);
                    item.put("result", syncResult);
                    succeeded++;
                } catch (Exception e) {
                    item.put("success", false);
                    item.put("error", e.getMessage());
                    if (!continueOnError) {
                        results.add(item);
                        break;
                    }
                }
                results.add(item);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("operation_id", newOperationId("sync-bulk"));
            response.put("timestamp", nowMs());
            response.put("total", results.size());
            response.put("succeeded", succeeded);
            response.put("failed", Math.max(0, results.size() - succeeded));
            response.put("results", results);

            sendSuccessResponse(exchange, response);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_control/sync-bulk endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleSyncPreflight(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }

            Map<String, String> params = parseQueryParams(exchange);
            String path = params.get("path");
            boolean current = Boolean.parseBoolean(params.getOrDefault("current", "false"));
            boolean autoAdd = Boolean.parseBoolean(params.getOrDefault("auto_add", "true"));

            DomainFile file;
            if (current || path == null || path.isEmpty()) {
                Program program = getCurrentProgram();
                if (program == null || program.getDomainFile() == null) {
                    sendErrorResponse(exchange, 400, "No current program domain file is available", "NO_PROGRAM_LOADED");
                    return;
                }
                file = program.getDomainFile();
            } else {
                Project project = tool.getProject();
                if (project == null) {
                    sendErrorResponse(exchange, 503, "No project is currently open", "NO_PROJECT_OPEN");
                    return;
                }
                file = project.getProjectData().getFile(path);
                if (file == null) {
                    sendErrorResponse(exchange, 404, "File not found: " + path, "FILE_NOT_FOUND");
                    return;
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("operation_id", newOperationId("sync-preflight"));
            result.put("timestamp", nowMs());
            result.put("path", file.getPathname());
            result.put("name", file.getName());
            result.put("is_versioned", file.isVersioned());
            result.put("is_checked_out", file.isCheckedOut());
            result.put("latest_version", file.getLatestVersion());
            result.put("auto_add", autoAdd);
            result.put("would_add", !file.isVersioned() && autoAdd);
            result.put("would_checkout", !file.isCheckedOut());
            result.put("would_checkin", true);
            result.put("has_local_changes", hasLocalChanges(file));

            sendSuccessResponse(exchange, result);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_control/sync-preflight endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleCheckoutFile(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }
            Map<String, String> params = parseJsonPostParams(exchange);
            DomainFile file = resolveTargetFile(params);
            boolean exclusive = Boolean.parseBoolean(params.getOrDefault("exclusive", "false"));

            boolean checkedOut = file.isCheckedOut() || file.checkout(exclusive, new ConsoleTaskMonitor());
            if (!checkedOut) {
                throw new IllegalStateException("Checkout failed");
            }

            Map<String, Object> result = baseOperationResult("checkout", file);
            result.put("exclusive", exclusive);
            result.put("checked_out", true);
            sendSuccessResponse(exchange, result);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_control/checkout endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleCheckinFile(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }
            Map<String, String> params = parseJsonPostParams(exchange);
            DomainFile file = resolveTargetFile(params);
            String comment = params.getOrDefault("comment", "Checked in via GhydraMCP");
            boolean keepCheckedOut = Boolean.parseBoolean(params.getOrDefault("keep_checked_out", "false"));

            if (!file.isCheckedOut()) {
                throw new IllegalStateException("File is not checked out");
            }
            file.checkin(new CheckinHandler() {
                @Override
                public boolean keepCheckedOut() { return keepCheckedOut; }
                @Override
                public String getComment() { return comment; }
                @Override
                public boolean createKeepFile() { return false; }
            }, new ConsoleTaskMonitor());

            Map<String, Object> result = baseOperationResult("checkin", file);
            result.put("comment", comment);
            result.put("keep_checked_out", keepCheckedOut);
            result.put("checked_in", true);
            sendSuccessResponse(exchange, result);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_control/checkin endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleUndoCheckoutFile(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }
            Map<String, String> params = parseJsonPostParams(exchange);
            DomainFile file = resolveTargetFile(params);
            boolean keep = Boolean.parseBoolean(params.getOrDefault("keep", "false"));

            if (!file.isCheckedOut()) {
                throw new IllegalStateException("File is not checked out");
            }
            file.undoCheckout(keep);

            Map<String, Object> result = baseOperationResult("undo-checkout", file);
            result.put("kept_copy", keep);
            result.put("undone", true);
            sendSuccessResponse(exchange, result);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_control/undo_checkout endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleFileStatus(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }
            Map<String, String> params = parseQueryParams(exchange);
            DomainFile file = resolveTargetFile(params);

            Map<String, Object> result = baseOperationResult("file-status", file);
            result.put("is_versioned", file.isVersioned());
            result.put("is_checked_out", file.isCheckedOut());
            result.put("is_checked_out_exclusive", file.isCheckedOutExclusive());
            result.put("is_read_only", file.isReadOnly());
            result.put("version", file.getVersion());
            result.put("latest_version", file.getLatestVersion());
            result.put("has_local_changes", hasLocalChanges(file));
            sendSuccessResponse(exchange, result);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_control/file-status endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleRepositoryFiles(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }
            Map<String, String> params = parseQueryParams(exchange);
            String folderPath = params.getOrDefault("folder", "/");
            boolean recursive = Boolean.parseBoolean(params.getOrDefault("recursive", "true"));
            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);

            Project project = tool.getProject();
            if (project == null) {
                sendErrorResponse(exchange, 503, "No project is currently open", "NO_PROJECT_OPEN");
                return;
            }
            DomainFolder folder = project.getProjectData().getFolder(folderPath);
            if (folder == null) {
                sendErrorResponse(exchange, 404, "Folder not found: " + folderPath, "FOLDER_NOT_FOUND");
                return;
            }

            List<Map<String, Object>> items = new ArrayList<>();
            if (recursive) {
                List<DomainFile> allFiles = new ArrayList<>();
                collectDomainFiles(folder, allFiles);
                for (DomainFile file : allFiles) {
                    items.add(createFileInfo(file, project));
                }
            } else {
                for (DomainFile file : folder.getFiles()) {
                    items.add(createFileInfo(file, project));
                }
            }

            ResponseBuilder builder = new ResponseBuilder(exchange, port).success(true);
            List<Map<String, Object>> paginated = applyPagination(items, offset, limit, builder,
                "/server/repository/files", "folder=" + folderPath + "&recursive=" + recursive);

            Map<String, Object> result = new HashMap<>();
            result.put("operation_id", newOperationId("repository-files"));
            result.put("timestamp", nowMs());
            result.put("folder", folderPath);
            result.put("recursive", recursive);
            result.put("items", paginated);
            builder.result(result);
            sendJsonResponse(exchange, builder.build(), 200);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/repository/files endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void handleVersionHistory(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed", "METHOD_NOT_ALLOWED");
                return;
            }
            Map<String, String> params = parseQueryParams(exchange);
            DomainFile file = resolveTargetFile(params);

            Object versionsObj;
            try {
                java.lang.reflect.Method getVersionHistory = file.getClass().getMethod("getVersionHistory");
                versionsObj = getVersionHistory.invoke(file);
            } catch (Exception ex) {
                throw new IllegalStateException("Version history is not available in this Ghidra build: " + ex.getMessage());
            }
            List<Map<String, Object>> history = new ArrayList<>();
            if (versionsObj instanceof Object[]) {
                for (Object version : (Object[]) versionsObj) {
                    Map<String, Object> item = new HashMap<>();
                    try {
                        java.lang.reflect.Method getVersion = version.getClass().getMethod("getVersion");
                        java.lang.reflect.Method getUser = version.getClass().getMethod("getUser");
                        java.lang.reflect.Method getComment = version.getClass().getMethod("getComment");
                        java.lang.reflect.Method getCreateTime = version.getClass().getMethod("getCreateTime");
                        Object createTime = getCreateTime.invoke(version);

                        item.put("version", getVersion.invoke(version));
                        item.put("user", getUser.invoke(version));
                        item.put("comment", getComment.invoke(version));
                        if (createTime instanceof Number) {
                            item.put("date", new java.util.Date(((Number) createTime).longValue()).toString());
                        } else {
                            item.put("date", String.valueOf(createTime));
                        }
                    } catch (Exception ex) {
                        item.put("raw", String.valueOf(version));
                    }
                    history.add(item);
                }
            }

            Map<String, Object> result = baseOperationResult("version-history", file);
            result.put("history", history);
            result.put("count", history.size());
            sendSuccessResponse(exchange, result);
        } catch (Exception e) {
            Msg.error(this, "Error in /server/version_history endpoint", e);
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private Object getProjectRepository() {
        try {
            Project project = tool.getProject();
            if (project == null) {
                return null;
            }
            ProjectData data = project.getProjectData();
            java.lang.reflect.Method m = data.getClass().getMethod("getRepository");
            return m.invoke(data);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> syncDomainFileToServer(DomainFile file, String comment,
                                                        boolean autoAdd,
                                                        boolean keepCheckedOut,
                                                        boolean exclusiveCheckout,
                                                        boolean force,
                                                        boolean failIfModified,
                                                        boolean allowMerge) throws Exception {
        Object repo = getProjectRepository();
        if (repo == null) {
            throw new IllegalStateException("Current project is not connected to a shared Ghidra Server repository");
        }

        Map<String, Object> result = baseOperationResult("sync", file);
        result.put("auto_add", autoAdd);
        result.put("keep_checked_out", keepCheckedOut);
        result.put("force", force);
        result.put("fail_if_modified", failIfModified);
        result.put("allow_merge", allowMerge);

        boolean added = false;
        boolean checkedOut = false;
        boolean checkedIn = false;

        if (failIfModified && hasLocalChanges(file)) {
            throw new IllegalStateException("Local changes detected and fail_if_modified=true");
        }

        if (!file.isVersioned()) {
            if (!autoAdd) {
                throw new IllegalStateException("File is not under version control and auto_add is false");
            }
            file.addToVersionControl(comment, false, new ConsoleTaskMonitor());
            added = true;
        }

        if (!file.isCheckedOut()) {
            checkedOut = file.checkout(exclusiveCheckout, new ConsoleTaskMonitor());
            if (!checkedOut && !force) {
                throw new IllegalStateException("Checkout failed for file: " + file.getPathname());
            }
            if (!checkedOut && force) {
                checkedOut = file.isCheckedOut();
            }
        } else {
            checkedOut = true;
        }

        file.checkin(new CheckinHandler() {
            @Override
            public boolean keepCheckedOut() {
                return keepCheckedOut;
            }

            @Override
            public String getComment() {
                return comment;
            }

            @Override
            public boolean createKeepFile() {
                return false;
            }
        }, new ConsoleTaskMonitor());
        checkedIn = true;

        result.put("added_to_server", added);
        result.put("checked_out", checkedOut);
        result.put("checked_in", checkedIn);
        result.put("versioned", file.isVersioned());
        result.put("latest_version", file.getLatestVersion());
        result.put("message", added
            ? "File added to server and synced successfully"
            : "File synced successfully");

        return result;
    }

    private Map<String, Object> baseOperationResult(String operation, DomainFile file) {
        Map<String, Object> result = new HashMap<>();
        result.put("operation", operation);
        result.put("operation_id", newOperationId(operation));
        result.put("timestamp", nowMs());
        result.put("path", file.getPathname());
        result.put("name", file.getName());
        return result;
    }

    private String newOperationId(String prefix) {
        return prefix + "-" + nowMs() + "-" + OP_COUNTER.getAndIncrement();
    }

    private long nowMs() {
        return System.currentTimeMillis();
    }

    private boolean hasLocalChanges(DomainFile file) {
        try {
            java.lang.reflect.Method m = file.getClass().getMethod("isChanged");
            Object value = m.invoke(file);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private DomainFile resolveTargetFile(Map<String, String> params) throws Exception {
        String path = params.get("path");
        boolean current = Boolean.parseBoolean(params.getOrDefault("current", "false"));
        if (current || path == null || path.isEmpty()) {
            Program program = getCurrentProgram();
            if (program == null || program.getDomainFile() == null) {
                throw new IllegalStateException("No current program domain file is available");
            }
            return program.getDomainFile();
        }

        Project project = tool.getProject();
        if (project == null) {
            throw new IllegalStateException("No project is currently open");
        }
        DomainFile file = project.getProjectData().getFile(path);
        if (file == null) {
            throw new IllegalStateException("File not found: " + path);
        }
        return file;
    }

    /**
     * Create file information map
     */
    private Map<String, Object> createFileInfo(DomainFile file, Project project) {
        Map<String, Object> fileInfo = new HashMap<>();
        fileInfo.put("name", file.getName());
        fileInfo.put("path", file.getPathname());
        fileInfo.put("type", "file");

        String contentType = file.getContentType();
        fileInfo.put("contentType", contentType);

        // Check if this is a Program file (contentType could be "Program" or "ghidra.program.model.listing.Program")
        boolean isProgram = contentType != null &&
                           (contentType.equals("Program") ||
                            contentType.equals(Program.class.getName()) ||
                            contentType.endsWith(".Program"));

        fileInfo.put("isProgram", isProgram);

        if (isProgram) {
            // Check if program is open
            ProgramManager programManager = tool.getService(ProgramManager.class);
            if (programManager != null) {
                for (Program p : programManager.getAllOpenPrograms()) {
                    if (p.getDomainFile().equals(file)) {
                        fileInfo.put("isOpen", true);
                        break;
                    }
                }
            }
        }

        fileInfo.put("fileID", file.getFileID());
        fileInfo.put("version", file.getVersion());
        fileInfo.put("modificationTime", file.getLastModifiedTime());

        return fileInfo;
    }

    /**
     * Recursively collect all domain files
     */
    private void collectDomainFiles(DomainFolder folder, List<DomainFile> files) {
        for (DomainFile file : folder.getFiles()) {
            files.add(file);
        }

        for (DomainFolder subFolder : folder.getFolders()) {
            collectDomainFiles(subFolder, files);
        }
    }

    /**
     * Count files and folders recursively
     */
    private int[] countFilesAndFolders(DomainFolder folder) {
        int fileCount = folder.getFiles().length;
        int folderCount = folder.getFolders().length;

        for (DomainFolder subFolder : folder.getFolders()) {
            int[] subCounts = countFilesAndFolders(subFolder);
            fileCount += subCounts[0];
            folderCount += subCounts[1];
        }

        return new int[]{fileCount, folderCount};
    }
}
