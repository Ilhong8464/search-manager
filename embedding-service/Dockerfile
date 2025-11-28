# Base image
FROM python:3.9-slim

# Set working directory
WORKDIR /app

# Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# Install uvicorn if not in requirements (it should be, but just in case)
RUN pip install uvicorn

# Copy application code
COPY . .

# Expose port
EXPOSE 8000

# Run the application
CMD ["uvicorn", "embedding_service:app", "--host", "0.0.0.0", "--port", "8000"]
