package com.mccompanion.runtime.db;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;

/** One-way startup reconciliation for persisted plans from the removed internal Agent. */
public final class LegacyAgentStateMigrator {
  private final RuntimeDatabase database;
  private final Clock clock;

  public LegacyAgentStateMigrator(RuntimeDatabase database) {
    this(database, Clock.systemUTC());
  }

  LegacyAgentStateMigrator(RuntimeDatabase database, Clock clock) {
    this.database = java.util.Objects.requireNonNull(database, "database");
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
  }

  public int pauseRunningForExternalBrainMigration() throws SQLException {
    long now = clock.millis();
    try (var connection = database.open()) {
      connection.setAutoCommit(false);
      try (PreparedStatement steps = connection.prepareStatement("""
          UPDATE agent_step SET state='PAUSED',failure_code='INTERNAL_AGENT_REMOVED',updated_at=?
          WHERE state='RUNNING'
          """); PreparedStatement plans = connection.prepareStatement("""
          UPDATE agent_plan SET state='PAUSED',revision=revision+1,updated_at=?
          WHERE state='RUNNING'
          """)) {
        steps.setLong(1, now);
        steps.executeUpdate();
        plans.setLong(1, now);
        int changed = plans.executeUpdate();
        connection.commit();
        return changed;
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }
}
