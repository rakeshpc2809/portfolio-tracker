FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
# Install CPU-only torch to save space (no NVIDIA GPU detected)
RUN pip install --no-cache-dir --default-timeout=1000 torch --index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir --default-timeout=1000 -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
