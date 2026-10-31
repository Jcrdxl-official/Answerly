FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app

ENV RELAY_API_KEYS=dev-api-key
ENV RELAY_DEVICE_KEYS=android-1:dev-device-key
ENV RELAY_DEVICE_IDS=android-1
ENV RELAY_TASK_TIMEOUT_SECONDS=120

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

