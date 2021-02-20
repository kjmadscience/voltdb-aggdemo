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

import org.voltdb.SQLStmt;
import org.voltdb.VoltTable;
import org.voltdb.types.TimestampType;

/**
 * Return information about a session
 */
public class GetBySessionId extends AbstractMediationProcedure {

	// @formatter:off

	public static final SQLStmt getSession = new SQLStmt(
			"SELECT d.*, sequenceToString(used_seqno_array) seqnos_used "
			+ ", getHighestValidSequence(used_seqno_array) highest_valid_seqno "
			+ "FROM cdr_dupcheck d "
			+ "WHERE sessionId = ? AND sessionStartUTC = ?;");

	public static final SQLStmt getSessionRunningTotals = new SQLStmt(
			"SELECT * FROM unaggregated_cdrs_by_session WHERE sessionId = ? AND sessionStartUTC = ?;");
			
	// @formatter:on

	public VoltTable[] run(long sessionId, TimestampType sessionStartUTC) throws VoltAbortException {

		voltQueueSQL(getSession, sessionId, sessionStartUTC);
		voltQueueSQL(getSessionRunningTotals, sessionId, sessionStartUTC);

		return voltExecuteSQL(true);

	}

}
