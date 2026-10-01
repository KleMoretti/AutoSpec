FROM node:22-bookworm-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && npm install --global typescript@5.7.2 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip3 install --no-cache-dir --break-system-packages -r requirements.txt
COPY . .

RUN groupadd --system autospec \
    && useradd --system --gid autospec autospec \
    && chown -R autospec:autospec /app
USER autospec

EXPOSE 8010
CMD ["python3", "-m", "uvicorn", "spec_verifier.service:app", "--host", "0.0.0.0", "--port", "8010"]
