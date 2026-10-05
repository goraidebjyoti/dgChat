FROM python:3.12.9-slim
WORKDIR /relay
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py limits.py ./
USER 65534:65534
EXPOSE 8765
CMD ["python", "server.py", "--host", "0.0.0.0"]
