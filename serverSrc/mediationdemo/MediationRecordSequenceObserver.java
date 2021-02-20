package mediationdemo;

import org.voltdb.aggdemo.MediationSession;

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

public class MediationRecordSequenceObserver {

	public int getHighestValidSequence(byte[] varbinaryArray) {

		MediationRecordSequence msr = new MediationRecordSequence(varbinaryArray);

		int seqnoCount = msr.theBitSet.cardinality();

		if (seqnoCount == 0) {
			return -1;
		}

		if (msr.weHaveFromZeroTo(seqnoCount - 1)) {
			return seqnoCount - 1;
		}

		return -1;

	}

	public String getSeqnosAsText(byte[] varbinaryArray) {

		int highestValidSequence = getHighestValidSequence(varbinaryArray);
		StringBuffer b = new StringBuffer();

		if (highestValidSequence > -1) {

			b.append("0-");
			b.append(highestValidSequence);
			return b.toString();

		}

		MediationRecordSequence msr = new MediationRecordSequence(varbinaryArray);

		return (msr.toString());

	}
}
