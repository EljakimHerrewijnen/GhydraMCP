#!/bin/bash
set -e

# Script to run GhydraMCP tests with Ghidra GUI
# This is simpler than headless mode for testing

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
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo "=========================================="
echo "GhydraMCP Test Runner"
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

echo ""
echo "=========================================="
echo "Instructions"
echo "=========================================="
echo ""
echo -e "${BLUE}1. Start Ghidra GUI:${NC}"
echo "   $GHIDRA_HOME/ghidraRun"
echo ""
echo -e "${BLUE}2. Open the project:${NC}"
echo "   File -> Open Project -> $PROJECT_DIR/$PROJECT_NAME"
echo ""
echo -e "${BLUE}3. The GhydraMCP extension will start automatically${NC}"
echo "   Server will be at http://localhost:8192"
echo ""
echo -e "${BLUE}4. Run tests in another terminal:${NC}"
echo "   cd /home/eljakim/Source/GhydraMCP"
echo "   python3 test_memory_operations.py"
echo ""
echo -e "${YELLOW}Note: The extension starts automatically when a project is opened.${NC}"
echo ""
echo "Press Enter to start Ghidra GUI now, or Ctrl+C to exit..."
read

# Start Ghidra GUI
echo ""
echo "Starting Ghidra GUI..."
"$GHIDRA_HOME/ghidraRun" &

echo ""
echo -e "${GREEN}Ghidra started!${NC}"
echo "Please open the project and wait for the extension to start."
echo "Then run the tests in another terminal."