#!/bin/bash
# Text Editor Launcher

# Compile if needed
echo "Compiling..."
javac -d build src/elfin/CursorCtrl.java src/elfin/KeyboardInput.java src/elfin/TextEditor.java
cp  src/elfin/*.txt build/elfin/

# Run the editor
echo "Starting Text Editor (Press ESC to exit)..."
sleep 1

# Create the error pipe if it doesn't exist
if [ ! -p /tmp/error_pipe ]; then
    mkfifo /tmp/error_pipe
fi

# Start background reader that logs to a file
while true; do cat /tmp/error_pipe; done >> /tmp/errors.log 2>&1 &
READER_PID=$!

java -cp build elfin.TextEditor 2> /tmp/error_pipe

# Kill the background reader when done
kill $READER_PID 2>/dev/null