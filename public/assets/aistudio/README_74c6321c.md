---
title: Lenscribe
colorFrom: blue
colorTo: indigo
sdk: docker
app_port: 7860
pinned: false
---

# Lenscribe

Lenscribe turns images of documents into editable text and exports them to common formats.

This Space runs the web application in a Docker container. Optional OCR providers need their corresponding configuration in the Space Settings secrets and variables. For example, set `GEMINI_API_KEY` to enable Gemini. Local Ollama and Windows OCR are disabled unless separately configured and available.

The application listens on port 7860 in Hugging Face Spaces. Its waitlist database uses the configured `DATA_DIR`; Space container storage may not persist across restarts unless persistent storage is enabled.
