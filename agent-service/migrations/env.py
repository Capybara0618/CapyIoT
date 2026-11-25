from alembic import context
from sqlalchemy import create_engine, pool

from app.config import DatabaseSettings


def run():
    config = DatabaseSettings()
    engine = create_engine(config.database_url, poolclass=pool.NullPool, hide_parameters=True)
    try:
        with engine.connect() as connection:
            connection.exec_driver_sql("SET time_zone = '+00:00'")
            connection.commit()
            context.configure(connection=connection, target_metadata=None, compare_type=True)
            with context.begin_transaction():
                context.run_migrations()
    finally:
        engine.dispose()


run()
