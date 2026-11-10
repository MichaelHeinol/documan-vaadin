FROM python:3.13-slim-trixie

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      fonts-dejavu-core \
      fonts-liberation \
      libharfbuzz-subset0 \
      libpango-1.0-0 \
      libpangoft2-1.0-0 \
 && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir markdown-it-py==3.0.0 weasyprint==65.1

COPY anleitung_pdf.py /anleitung_pdf.py
WORKDIR /app
ENTRYPOINT ["python", "/anleitung_pdf.py"]
