FROM python:3.11-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    gcc \
    sqlite3 \
    && rm -rf /var/lib/apt/lists/*

# Install Python requirements. VieNeu-TTS (giọng đọc chạy trên máy chủ, ONNX/CPU, không torch) cài riêng bằng --no-deps:
# gói khai báo gradio + librosa chỉ cho giao diện demo/nhân bản giọng, đường đọc truyện không dùng. Mô hình (Nano ~270 MB,
# Turbo ~550 MB) tải lần đầu khi chạy vào /app/data/hf (volume ./data), không nằm trong image.
COPY requirements.txt requirements-vieneu.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir --no-deps -r requirements-vieneu.txt

# Copy application files
COPY . .

# Expose FastAPI port
EXPOSE 8000

# Healthcheck
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8000/v1/health || exit 1

# Run server
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000"]
