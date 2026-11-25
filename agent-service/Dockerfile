FROM python:3.12.12-slim-bookworm
COPY --from=ghcr.io/astral-sh/uv:0.10.8 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_PYTHON_DOWNLOADS=never UV_LINK_MODE=copy PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY app ./app
COPY alembic.ini ./alembic.ini
COPY migrations ./migrations
RUN useradd --create-home --uid 10001 agent && mkdir /keys && chown agent:agent /keys
USER agent
ENV PATH="/app/.venv/bin:$PATH"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8086", "--no-access-log", "--log-level", "info"]
