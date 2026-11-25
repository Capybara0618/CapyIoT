from sqlalchemy import create_engine, event, text
from sqlalchemy.orm import sessionmaker

from app.config import Settings


class Database:
    def __init__(self, settings: Settings):
        self.engine = create_engine(
            settings.database_url,
            pool_size=5,
            max_overflow=0,
            pool_timeout=5,
            pool_pre_ping=True,
            hide_parameters=True,
            connect_args={"connect_timeout": 5, "read_timeout": 10, "write_timeout": 10},
        )
        self.sessions = sessionmaker(self.engine, expire_on_commit=False)

        @event.listens_for(self.engine, "connect")
        def utc_connection(connection, _):
            with connection.cursor() as cursor:
                cursor.execute("SET time_zone = '+00:00'")

    def ready(self):
        with self.engine.connect() as connection:
            connection.execute(text("SELECT 1"))
            version = connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one()
            if version != "0002_query_evidence":
                raise RuntimeError("Agent schema migration is required")

    def close(self):
        self.engine.dispose()
