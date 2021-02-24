package mediationdemo;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2021 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

import java.util.Date;
import org.voltdb.SQLStmt;
import org.voltdb.VoltTable;
import org.voltdb.types.TimestampType;

/**
 * This runs on each partition as a DIRECTED PROCEDURE and is kicked off by a
 * TASK.
 * 
 * It finds sessions that either didn't get closed or are very late and
 * terminates them.
 *
 */
public class FlushStaleSessions extends AbstractMediationProcedure {

    // @formatter:off

	public static final SQLStmt getOldestUnfinishedSession = new SQLStmt(
			"SELECT min_recordStartUTC FROM unaggregated_cdrs_by_session ORDER BY min_recordStartUTC LIMIT 1;");

	public static final SQLStmt getSessionRunningTotals = new SQLStmt(
			"SELECT  u.*, how_many - (max_seqno - min_seqno + 1) missingCdrCount "
			+ "from unaggregated_cdrs_by_session u WHERE min_recordStartUTC BETWEEN ? AND ? "
			+ "order by min_recordStartUTC,sessionId, sessionStartUTC LIMIT ?;");

	// @formatter:on

    long stalenessThresholdMs = 300000;
    long aggWindowSizeMs = 2000;
    int rowLimit = 1000;

    public VoltTable[] run() throws VoltAbortException {

        // find oldest unaggregated session
        voltQueueSQL(getOldestUnfinishedSession);

        // Find parameters...
        voltQueueSQL(getParameter, STALENESS_THRESHOLD_MS);
        voltQueueSQL(getParameter, AGG_WINDOW_SIZE_MS);
        voltQueueSQL(getParameter, STALENESS_ROWLIMIT);

        VoltTable[] queryResults = voltExecuteSQL();
        VoltTable oldestSessionDateTable = queryResults[0];
        VoltTable stalenessThresholdMsTable = queryResults[1];
        VoltTable aggWindowSizeMsTable = queryResults[2];
        VoltTable rowLimitTable = queryResults[3];

        // Sessions have to be at LEAST stalenessThresholdMs stale before we cancel
        // them..
        stalenessThresholdMs = getParameterIfSet(stalenessThresholdMsTable, stalenessThresholdMs);

        // When we cancel records we use a window aggWindowSizeMs in size...
        aggWindowSizeMs = getParameterIfSet(aggWindowSizeMsTable, aggWindowSizeMs);

        // See how many rows we do in one pass. More isn't always better...
        rowLimit = (int) getParameterIfSet(rowLimitTable, rowLimit);

        // Do not mess with records that were changed less than stalenessThresholdMs
        // ago...
        final Date cutoffDate = new Date(this.getTransactionTime().getTime() - stalenessThresholdMs);

        if (oldestSessionDateTable.advanceRow()) {

            final TimestampType oldestSessionDate = oldestSessionDateTable
                    .getTimestampAsTimestamp("min_recordStartUTC");

            // if we can find at least one old session and it's old enough to cancel...
            if (oldestSessionDate != null && oldestSessionDate.asExactJavaDate().before(cutoffDate)) {

                // figure out time period we'll check this pass, and make sure it isn't too
                // big...
                Date aggWindowCloseDate = new Date(oldestSessionDate.asExactJavaDate().getTime() + aggWindowSizeMs);

                if (aggWindowCloseDate.after(cutoffDate)) {
                    aggWindowCloseDate = cutoffDate;
                }

                // Find our sessions to cancel...

                voltQueueSQL(getSessionRunningTotals, oldestSessionDate, aggWindowCloseDate, rowLimit);
                VoltTable sessionsToClose = voltExecuteSQL()[0];
                System.out.println(oldestSessionDate.toString() + " " + aggWindowCloseDate.toGMTString() + " "
                        + sessionsToClose.getRowCount());
                // For each session...
                while (sessionsToClose.advanceRow()) {

                    // See how many CDRS are missing.
                    long missingCdrCount = sessionsToClose.getLong("missingCdrCount");

                    // If none are missing it means an intermediate turned up and
                    // completed the set *after* the end record. Declare victory and
                    // aggregate. If not, cancel the session.
                    if (missingCdrCount == 0) {
                        aggregateSession(sessionsToClose, "AGE");
                    } else {
                        cancelLateSession(sessionsToClose);
                    }
                }

            }
        }
        return voltExecuteSQL(true);

    }

}
