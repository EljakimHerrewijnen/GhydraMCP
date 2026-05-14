#!/bin/bash
set -e

# Script to run Ghidra in headless mode with GhydraMCP extension and run tests

# Configuration
GHIDRA_HOME="/home/eljakim/WorkingEnvironment/Reversing/current_ghidra"
PROJECT_DIR="/tmp/ghidra_test_project"
PROJECT_NAME="test_project"
TEST_BINARY="/tmp/busybox-arm64"
PLUGIN_ZIP="$(find /home/eljakim/Source/GhydraMCP/target -name "GhydraMCP-*.zip" -not -name "*Complete*" | head -1)"
EXTENSION_DIR="$HOME/.config/ghidra/ghidra_12.0.4_PUBLIC/Extensions"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "GhydraMCP Headless Test Runner"
echo "=========================================="
echo ""

# Check prerequisites
echo "Checking prerequisites..."

if [ ! -d "$GHIDRA_HOME" ]; then
    echo -e "${RED}ERROR: Ghidra not found at $GHIDRA_HOME${NC}"
    exit 1
fi
echo -e "${GREEN}✓${NC} Ghidra found at $GHIDRA_HOME"

if [ ! -f "$TEST_BINARY" ]; then
    echo -e "${RED}ERROR: Test binary not found at $TEST_BINARY${NC}"
    exit 1
fi
echo -e "${GREEN}✓${NC} Test binary found at $TEST_BINARY"

if [ ! -f "$PLUGIN_ZIP" ]; then
    echo -e "${RED}ERROR: Plugin zip not found. Please build first with: mvn package${NC}"
    exit 1
fi
echo -e "${GREEN}✓${NC} Plugin zip found: $PLUGIN_ZIP"

# Install extension
echo ""
echo "Installing GhydraMCP extension..."
mkdir -p "$EXTENSION_DIR"
cp "$PLUGIN_ZIP" "$EXTENSION_DIR/GhydraMCP.zip"
echo -e "${GREEN}✓${NC} Extension installed to $EXTENSION_DIR"

# Clean up old project
echo ""
echo "Cleaning up old test project..."
rm -rf "$PROJECT_DIR"
mkdir -p "$PROJECT_DIR"

# Import binary and analyze
echo ""
echo "Importing and analyzing test binary..."
echo "Binary: $TEST_BINARY"
echo "Project: $PROJECT_DIR/$PROJECT_NAME"

"$GHIDRA_HOME/support/analyzeHeadless" \
    "$PROJECT_DIR" \
    "$PROJECT_NAME" \
    -import "$TEST_BINARY" \
    -processor "AARCH64:LE:default:default" \
    -noanalysis \
    -deleteProject \
    2>&1 | tee /tmp/ghidra_import.log

if [ ${PIPESTATUS[0]} -ne 0 ]; then
    echo -e "${RED}ERROR: Failed to import binary${NC}"
    echo "Check /tmp/ghidra_import.log for details"
    exit 1
fi

echo -e "${GREEN}✓${NC} Binary imported successfully"

# Run analysis
echo ""
echo "Running auto-analysis..."
"$GHIDRA_HOME/support/analyzeHeadless" \
    "$PROJECT_DIR" \
    "$PROJECT_NAME" \
    -process "$PROJECT_NAME" \
    2>&1 | tee /tmp/ghidra_analysis.log

if [ ${PIPESTATUS[0]} -ne 0 ]; then
    echo -e "${YELLOW}WARNING: Analysis had issues, but continuing...${NC}"
fi

echo -e "${GREEN}✓${NC} Analysis complete"

# Now we need to start Ghidra with the extension in headless mode
# This is tricky because the extension needs to start the HTTP server
# We'll use a script approach

echo ""
echo "=========================================="
echo "Starting Ghidra with GhydraMCP extension..."
echo "=========================================="

# Create a startup script that will run the extension
cat > /tmp/start_ghydra.py << 'EOF'
# @category GhydraMCP
# Start the GhydraMCP HTTP server

from eu.starsong.ghidra.GhydraMCPPlugin import GhydraMCPPlugin

plugin = GhydraMCPPlugin()
plugin.start()

print("GhydraMCP server started")
print("Press Ctrl+C to stop")

try:
    import time
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    print("\nStopping GhydraMCP server...")
    plugin.stop()
    print("Server stopped")
EOF

# Run Ghidra headless with the startup script
echo "Starting headless Ghidra with HTTP server..."
echo "Server will be available at http://localhost:8192"
echo ""
echo "Press Ctrl+C to stop the server"
echo ""

"$GHIDRA_HOME/support/analyzeHeadless" \
    "$PROJECT_DIR" \
    "$PROJECT_NAME" \
    -preScript "/tmp/start_ghydra.py" \
    -postScript "/tmp/stop_ghydra.py" \
    2>&1 &

GHIDRA_PID=$!
echo "Ghidra started with PID: $GHIDRA_PID"

# Wait for server to start
echo "Waiting for server to start..."
for i in {1..30}; do
    if curl -s http://localhost:8192/info > /dev/null 2>&1; then
        echo -e "${GREEN}✓${NC} Server is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}ERROR: Server failed to start${NC}"
        kill $GHIDRA_PID 2>/dev/null || true
        exit 1
    fi
    sleep 1
    echo -n "."
done
echo ""

# Run tests
echo ""
echo "=========================================="
echo "Running Memory Operations Tests"
echo "=========================================="
echo ""

cd /home/eljakim/Source/GhydraMCP
python3 test_memory_operations.py

TEST_RESULT=$?

# Cleanup
echo ""
echo "Cleaning up..."
kill $GHIDRA_PID 2>/dev/null || true
wait $GHIDRA_PID 2>/dev/null || true

if [ $TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}=========================================="
    echo "All tests passed!"
    echo "==========================================${NC}"
    exit 0
else
    echo -e "${RED}=========================================="
    echo "Some tests failed!"
    echo "==========================================${NC}"
    exit 1
fi