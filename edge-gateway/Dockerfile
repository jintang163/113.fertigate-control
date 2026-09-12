FROM python:3.11-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY config ./config

# 真实硬件时挂载 /dev/ttyS* 并以 config 覆盖
CMD ["python", "-m", "app.main", "-c", "config/gateway.example.yaml", "--simulate"]
