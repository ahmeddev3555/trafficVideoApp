FROM python:3.11-slim

WORKDIR /srv

# libgl1/libglib2.0-0 are runtime requirements of opencv-python-headless and easyocr's
# image-decoding stack on a minimal slim base image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
